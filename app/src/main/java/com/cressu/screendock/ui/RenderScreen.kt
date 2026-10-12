package com.cressu.screendock.ui

import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cressu.screendock.R
import com.cressu.screendock.bridge.NativeBridge
import com.cressu.screendock.renderer.ScaleMode
import com.cressu.screendock.renderer.VideoRenderer
import com.cressu.screendock.renderer.VideoSize
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.settings.SettingsRepository
import com.cressu.screendock.ui.components.StatusChip
import com.cressu.screendock.ui.components.focusGlow
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val CHIP_VISIBLE_MS = 3_500L
private const val TAP_MAX_MS = 350L
private const val RENDERER_POLL_MS = 100L

/**
 * Mirroring screen. A SurfaceView whose Surface is forwarded to the service's
 * VideoRenderer (VideoPipeline decodes to its own OES texture and blits onto
 * it). A transparent overlay forwards touches to the sender and — on a plain
 * tap — reveals a small auto-hiding chip that cycles Fit / Fill / Stretch.
 */
@Composable
fun RenderScreen(repo: SettingsRepository, savedScaleMode: String) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ScaleModes.parse(savedScaleMode)) }

    // The renderer can be (re)created after this screen composes; wait for it.
    var renderer by remember { mutableStateOf(AirPlayService.videoRenderer) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = AirPlayService.videoRenderer
            if (current !== renderer) renderer = current
            delay(RENDERER_POLL_MS)
        }
    }
    var surface by remember { mutableStateOf<Surface?>(null) }
    LaunchedEffect(renderer, surface) {
        // Hand the Surface to whichever renderer is current, even if it appeared late.
        renderer?.setSurface(surface)
    }
    LaunchedEffect(renderer, mode) { renderer?.scaleMode = mode }

    val sizeFlow = remember(renderer) { renderer?.videoSize ?: MutableStateFlow(VideoSize.UNKNOWN) }
    val videoSize by sizeFlow.collectAsState()

    var chipNonce by remember { mutableIntStateOf(0) }
    var chipVisible by remember { mutableStateOf(false) }
    LaunchedEffect(chipNonce) {
        if (chipNonce == 0) return@LaunchedEffect
        chipVisible = true
        delay(CHIP_VISIBLE_MS)
        chipVisible = false
    }

    val isTv = LocalIsTv.current
    val rootFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(chipVisible, isTv) {
        if (!isTv) return@LaunchedEffect
        runCatching { if (chipVisible) chipFocus.requestFocus() else rootFocus.requestFocus() }
    }

    val cycle = {
        val next = ScaleModes.next(mode)
        mode = next
        renderer?.scaleMode = next
        chipNonce++ // keep the chip up after interaction
        scope.launch { repo.updateScaleMode(next.name) }
        Unit
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                // D-pad / remote: any navigation key reveals the quick controls.
                val isNav = e.key.nativeKeyCode in NAV_KEYS
                if (e.type == KeyEventType.KeyDown && isNav) {
                    chipNonce++
                    true
                } else false
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surface = null
                            // Immediate (not via the effect): the Surface is invalid as of now.
                            AirPlayService.videoRenderer?.setSurface(null)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        TouchForwardOverlay(
            mode = mode,
            videoAspect = videoSize.aspect,
            onTap = { chipNonce++ },
            modifier = Modifier.fillMaxSize(),
        )
        ConnectedChip()
        // Composed AFTER the overlay so it sits on top and wins hit-testing —
        // taps on the chip never reach the touch-forward layer.
        ScaleChip(
            visible = chipVisible,
            mode = mode,
            onClick = cycle,
            focusRequester = chipFocus,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
        )
    }
}

private val NAV_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_DPAD_UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_MENU,
)

@Composable
private fun scaleLabel(mode: ScaleMode): String = when (mode) {
    ScaleMode.FIT -> stringResource(R.string.scale_fit)
    ScaleMode.FILL -> stringResource(R.string.scale_fill)
    ScaleMode.STRETCH -> stringResource(R.string.scale_stretch)
}

@Composable
private fun ScaleChip(
    visible: Boolean,
    mode: ScaleMode,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(350)),
    ) {
        val label = scaleLabel(mode)
        val description = stringResource(R.string.cd_scale_mode, label)
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .focusRequester(focusRequester)
                .focusGlow(CircleShape)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.72f))
                .border(1.dp, Palette.white.copy(alpha = 0.18f), CircleShape)
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AspectRatio, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.textPrimary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.scale_tap_to_change), style = MaterialTheme.typography.bodySmall, color = Palette.textSecondary)
        }
    }
}

/** Brief confirmation when a session starts; fades away to pure video. */
@Composable
private fun ConnectedChip() {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(2400)
        visible = false
    }
    // Non-interactive full-size layer (no pointer input → hit test falls
    // through to the touch-forward overlay underneath).
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp),
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(500)) + shrinkVertically(tween(400)),
        ) {
            StatusChip(text = stringResource(R.string.mirroring_active), color = Palette.ready, pulse = false)
        }
    }
}

/**
 * Transparent gesture layer over the mirrored video. Maps view coordinates to
 * normalized [0..1] coordinates on the sender's screen (honouring Fit / Fill /
 * Stretch via [TouchMapper]), streams touchStart/Move/End into the sender, and
 * reports short stationary taps via [onTap]. Events are observed on the
 * Initial pass and never consumed, so nothing here blocks other layers.
 */
@Composable
private fun TouchForwardOverlay(
    mode: ScaleMode,
    videoAspect: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewW by remember { mutableStateOf(0) }
    var viewH by remember { mutableStateOf(0) }
    val currentMode by rememberUpdatedState(mode)
    val currentAspect by rememberUpdatedState(videoAspect)
    val currentOnTap by rememberUpdatedState(onTap)
    val slop = LocalViewConfiguration.current.touchSlop

    fun send(phase: Int, x: Float, y: Float) {
        val n = TouchMapper.normalize(x, y, viewW, viewH, currentMode, currentAspect) ?: run {
            Log.d("AirplayTouch", "gesture outside picture, ignored ($x,$y)")
            return
        }
        Log.d("AirplayTouch", "gesture phase=$phase norm=(${"%.3f".format(n.first)}, ${"%.3f".format(n.second)})")
        AirPlayService.sendTouch(phase, n.first, n.second)
    }

    Box(
        modifier = modifier
            .onSizeChanged { s -> viewW = s.width; viewH = s.height }
            .pointerInput(slop) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val startX = down.position.x
                    val startY = down.position.y
                    val startTime = down.uptimeMillis
                    send(NativeBridge.TOUCH_START, startX, startY)
                    var lastX = startX
                    var lastY = startY
                    var moved = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                lastX = change.position.x
                                lastY = change.position.y
                                if (kotlin.math.hypot(lastX - startX, lastY - startY) > slop) moved = true
                                send(NativeBridge.TOUCH_MOVE, lastX, lastY)
                            } else {
                                if (!moved && change.uptimeMillis - startTime <= TAP_MAX_MS) currentOnTap()
                                break
                            }
                        }
                    } finally {
                        send(NativeBridge.TOUCH_END, lastX, lastY)
                    }
                }
            },
    )
}
