package com.cressu.screendock.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cressu.screendock.billing.BillingManager
import com.cressu.screendock.bridge.NativeBridge
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.service.ReceiverState
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.settings.SettingsRepository
import com.cressu.screendock.ui.components.AuroraBackground
import com.cressu.screendock.ui.components.BroadcastHero
import com.cressu.screendock.ui.components.CapabilityChip
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.components.GlassCard
import com.cressu.screendock.ui.components.GlassIconButton
import com.cressu.screendock.ui.components.IconBadge
import com.cressu.screendock.ui.components.PulsingDot
import com.cressu.screendock.ui.components.StatusChip
import com.cressu.screendock.ui.theme.AirplayTheme
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.delay

private const val TAG = "AirplayMainActivity"

private enum class Screen { IDLE, SETTINGS }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: swaps the launch splash theme for
        // postSplashScreenTheme (Theme.Airplay) at the right moment.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // The UI is always dark — force light system-bar icons regardless
            // of the system theme, with transparent scrims over the aurora.
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val version = NativeBridge.versionString()
        Log.i(TAG, "Native version: $version")

        setContent {
            AirplayTheme {
                AppScreen(version = version, context = applicationContext)
            }
        }

        if (NativeBridge.isLoaded() && NativeBridge.selfTest()) {
            AirPlayService.start(this)
        } else {
            Log.e(TAG, "native selfTest failed; not starting service")
        }
    }
}

@Composable
private fun AppScreen(version: String, context: Context) {
    var screen by remember { mutableStateOf(Screen.IDLE) }
    // Process-wide StateFlow owned by AirPlayService — always current on
    // (re)composition, unlike the one-shot broadcast it replaces.
    val state by AirPlayService.state.collectAsState()
    val repo = remember(context) { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = AirPlaySettings())
    var selfTestOk by remember { mutableStateOf<Boolean?>(null) }
    val proUnlocked by AirPlayService.proUnlocked.collectAsState()
    val sessionLimitHit by AirPlayService.sessionLimitHit.collectAsState()
    val billing = remember(context) { BillingManager(context, repo) }

    LaunchedEffect(Unit) {
        if (!NativeBridge.isLoaded()) { selfTestOk = false; return@LaunchedEffect }
        selfTestOk = NativeBridge.selfTest()
        billing.start()
    }

    // POST_NOTIFICATIONS (Android 13+): the receiver's foreground notification
    // is its "still alive" signal — invisible until granted. Ask ONCE per
    // install (flag in SharedPreferences); permanent denials are respected and
    // the user can still grant from app info.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS " + if (granted) "granted" else "denied")
    }
    // NOTE: resolve the Activity via LocalContext — the `context` param above is
    // applicationContext, whose ContextWrapper chain contains no Activity.
    val activityContext = LocalContext.current
    LaunchedEffect(Unit) {
        val activity = activityContext.findActivity() ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            activity, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return@LaunchedEffect
        val prefs = activity.getSharedPreferences("ui_state", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_asked", false)) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Settings is an inner page — the system back gesture pops it, not the app.
    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.IDLE }

    // While mirroring: pure cinema — hide the system bars and keep the screen
    // on. Coming back: restore bars so the idle screen sits under normal insets.
    val view = LocalView.current
    LaunchedEffect(state) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (state == ReceiverState.CONNECTED) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Palette.bg) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (targetState == Screen.SETTINGS) {
                    (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 8 } + fadeOut(tween(180)))
                } else {
                    (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(200)) { it / 8 } + fadeOut(tween(180)))
                }
            },
            label = "screen",
        ) { current ->
            when (current) {
                Screen.SETTINGS -> SettingsScreen(
                    repo = repo,
                    onApply = {
                        // Single atomic restart command — stop()+start() races
                        // stopSelf() against the pending start and can kill the
                        // freshly restarted receiver (UI stuck on "Starting…").
                        AirPlayService.restart(context)
                        screen = Screen.IDLE
                    },
                    onBack = { screen = Screen.IDLE },
                    proUnlocked = proUnlocked,
                    onBuyPro = {
                        val activity = activityContext.findActivity()
                        if (activity != null) billing.launchPurchase(activity) { }
                        else Log.w(TAG, "purchase unavailable: no activity")
                    },
                )
                Screen.IDLE -> AnimatedContent(
                    targetState = state == ReceiverState.CONNECTED,
                    transitionSpec = {
                        (fadeIn(tween(320)) + scaleIn(initialScale = 0.96f, animationSpec = tween(320))) togetherWith
                            fadeOut(tween(200))
                    },
                    label = "mirroring",
                ) { mirroring ->
                    if (mirroring) RenderScreen()
                    else IdleScreen(
                        version = version,
                        state = state,
                        settings = settings,
                        selfTestOk = selfTestOk,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        proUnlocked = proUnlocked,
                        sessionLimitHit = sessionLimitHit,
                        onBuyPro = {
                            val activity = activityContext.findActivity()
                            if (activity != null) billing.launchPurchase(activity) { }
                            else Log.w(TAG, "purchase unavailable: no activity")
                        },
                        onDismissNudge = { AirPlayService.clearSessionLimitHit() },
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleScreen(
    version: String,
    state: ReceiverState,
    settings: AirPlaySettings,
    selfTestOk: Boolean?,
    onOpenSettings: () -> Unit,
    proUnlocked: Boolean,
    sessionLimitHit: Boolean,
    onBuyPro: () -> Unit,
    onDismissNudge: () -> Unit,
) {
    val failed = selfTestOk == false
    val probing = selfTestOk == null
    val ready = state == ReceiverState.WAITING

    val statusText = when {
        failed -> "Native core failed"
        probing -> "Waking receiver…"
        ready -> "Ready to receive"
        else -> "Starting receiver…"
    }
    val statusColor = when {
        failed -> Palette.error
        probing -> Palette.warn
        ready -> Palette.ready
        else -> Palette.accent
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BroadcastHero(rings = !failed, dim = failed)

            Spacer(Modifier.height(20.dp))

            Text(
                text = settings.displayName.ifBlank { "ScreenDock" },
                style = MaterialTheme.typography.displaySmall,
                color = Palette.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(statusColor, pulse = !failed)
                Spacer(Modifier.width(10.dp))
                Text(statusText, style = MaterialTheme.typography.labelLarge, color = statusColor)
            }

            Spacer(Modifier.height(36.dp))

            if (sessionLimitHit && !proUnlocked) {
                UpgradeNudgeCard(
                    onBuy = onBuyPro,
                    onDismiss = onDismissNudge,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp),
                )
                Spacer(Modifier.height(20.dp))
            }

            if (failed) {
                ErrorCard(Modifier.fillMaxWidth().widthIn(max = 620.dp))
            } else {
                ConnectGuideCard(
                    deviceName = settings.displayName.ifBlank { "ScreenDock" },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapabilityChip(resolutionLabel(settings.maxWidth, settings.maxHeight))
                CapabilityChip("${settings.maxFps} fps")
                if (settings.h265Enabled) CapabilityChip("H.265")
            }

            Spacer(Modifier.height(18.dp))

            Text(
                version,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.textTertiary,
            )
        }

        // MUST be composed after the scrollable column: a verticalScroll
        // container hit-tests its full bounds and would otherwise sit ON TOP
        // of this button and swallow every tap (button appears dead).
        GlassIconButton(
            icon = Icons.Rounded.Tune,
            contentDescription = "Settings",
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(20.dp),
        )
    }
}

private fun resolutionLabel(w: Int, h: Int): String = when {
    w == 0 -> "Native"
    h >= 2160 -> "4K"
    h >= 1080 -> "1080p"
    else -> "720p"
}

/** Upgrade nudge shown after a free-tier session hits the time limit. */
@Composable
private fun UpgradeNudgeCard(onBuy: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Session limit reached", style = MaterialTheme.typography.titleMedium, color = Palette.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Free sessions run ${AirPlayService.FREE_SESSION_MINUTES} minutes. ScreenDock Pro unlocks unlimited mirroring — one-time purchase, no subscriptions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textSecondary,
                )
            }
            GlassIconButton(icon = Icons.Rounded.Close, contentDescription = "Dismiss", onClick = onDismiss)
        }
        Spacer(Modifier.height(14.dp))
        GradientButton(
            text = "Unlock Pro — $9.99",
            icon = Icons.Rounded.Star,
            onClick = onBuy,
        )
    }
}

/** "Swipe down from the top-right… → Screen Mirroring → pick this device." */
@Composable
private fun ConnectGuideCard(deviceName: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Text(
            "HOW TO CONNECT",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.textTertiary,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GuideStep(
                icon = Icons.Rounded.PhoneIphone,
                title = "Control Center",
                subtitle = "Swipe down from the top-right corner of your iPhone or iPad.",
                modifier = Modifier.weight(1f),
            )
            GuideStep(
                icon = Icons.Rounded.ScreenShare,
                title = "Screen Mirroring",
                subtitle = "Tap the Screen Mirroring button in Control Center.",
                modifier = Modifier.weight(1f),
            )
            GuideStep(
                icon = Icons.Rounded.Cast,
                title = "Pick your device",
                subtitle = buildAnnotatedString {
                    append("Choose ")
                    withStyle(MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.W600).toSpanStyle()) {
                        append(deviceName)
                    }
                    append(" and mirroring begins.")
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.padding(horizontal = 3.dp))
            Text(
                "Both devices must be on the same Wi-Fi network.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun GuideStep(
    icon: ImageVector,
    title: String,
    subtitle: Any,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        IconBadge(icon)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.textPrimary)
        Spacer(Modifier.height(4.dp))
        when (subtitle) {
            is String -> Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.textSecondary)
            is androidx.compose.ui.text.AnnotatedString ->
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun ErrorCard(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = Palette.error,
                modifier = Modifier.size(26.dp),
            )
            Column {
                Text("Native core failed to start", style = MaterialTheme.typography.titleMedium, color = Palette.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Try reinstalling the app — this device may be missing required ABI support.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun RenderScreen() {
    // SurfaceView whose Surface is forwarded to the service's VideoRenderer.
    // VideoPipeline decodes to its own OES texture and blits onto this Surface.
    // The pointerInput overlay turns tablet touches into AirPlay HID events
    // injected into the sender (click/drag on the mirrored desktop).
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            AirPlayService.videoRenderer?.setSurface(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            AirPlayService.videoRenderer?.setSurface(null)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        TouchForwardOverlay(modifier = Modifier.fillMaxSize())
        ConnectedChip()
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
            StatusChip(text = "Mirroring active", color = Palette.ready, pulse = false)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Transparent gesture layer over the mirrored video. Maps view coordinates to
 * normalized [0..1] coordinates on the sender's screen by inverting the
 * letterbox/pillarbox rect the blit applies (video is centered and fit), then
 * streams touchStart/touchMove/touchEnd into the sender.
 */
@Composable
private fun TouchForwardOverlay(modifier: Modifier = Modifier) {
    var viewW by remember { mutableStateOf(0) }
    var viewH by remember { mutableStateOf(0) }

    fun normalize(x: Float, y: Float): Pair<Double, Double>? {
        if (viewW <= 0 || viewH <= 0) return null
        val va = AirPlayService.videoRenderer?.videoAspect() ?: 0f
        val vw = viewW.toFloat()
        val vh = viewH.toFloat()
        val viewAspect = vw / vh
        val videoW: Float
        val videoH: Float
        if (va > 0f) {
            if (va >= viewAspect) { videoW = vw; videoH = vw / va }
            else { videoH = vh; videoW = vh * va }
        } else {
            videoW = vw; videoH = vh
        }
        val nx = (x - (vw - videoW) / 2f) / videoW
        val ny = (y - (vh - videoH) / 2f) / videoH
        if (nx !in 0f..1f || ny !in 0f..1f) return null   // tapped a letterbox bar
        return nx.toDouble() to ny.toDouble()
    }

    fun send(phase: Int, x: Float, y: Float) {
        val n = normalize(x, y) ?: run {
            Log.d("AirplayTouch", "gesture in letterbox bar, ignored ($x,$y)")
            return
        }
        Log.d("AirplayTouch", "gesture phase=$phase norm=(${"%.3f".format(n.first)}, ${"%.3f".format(n.second)})")
        AirPlayService.sendTouch(phase, n.first, n.second)
    }

    Box(
        modifier = modifier
            .onSizeChanged { s -> viewW = s.width; viewH = s.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    send(NativeBridge.TOUCH_START, down.position.x, down.position.y)
                    var lastX = down.position.x
                    var lastY = down.position.y
                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                lastX = change.position.x
                                lastY = change.position.y
                                send(NativeBridge.TOUCH_MOVE, lastX, lastY)
                            } else {
                                break
                            }
                        }
                    } finally {
                        send(NativeBridge.TOUCH_END, lastX, lastY)
                    }
                }
            }
    )
}
