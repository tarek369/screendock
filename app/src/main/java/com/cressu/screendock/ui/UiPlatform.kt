package com.cressu.screendock.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.withFrameNanos

/** True on Android TV / leanback devices: larger type, D-pad-first affordances. */
val LocalIsTv = compositionLocalOf { false }

fun Context.isTelevision(): Boolean {
    val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** System "remove animations" / animator-duration-scale 0 → decorative motion off. */
fun Context.animationsEnabled(): Boolean = try {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
} catch (_: Exception) {
    true
}

/** Tracks whether the host lifecycle is RESUMED (decorative animations pause otherwise). */
@Composable
fun rememberIsResumed(): State<Boolean> {
    val owner = LocalLifecycleOwner.current
    val state = remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            state.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

private const val ANIMATION_FRAME_INTERVAL_NS = 33_000_000L // ~30 fps is plenty for ambient motion

/**
 * Looping phase in [0,1) driving ambient animations. Unlike an infinite
 * transition it (a) stops completely while the app is not resumed, (b) is
 * frame-limited to ~30 fps, and (c) stays frozen when the system animation
 * scale is 0. Read the returned state inside draw/graphicsLayer lambdas so
 * only the draw phase is invalidated.
 */
@Composable
fun rememberLoopPhase(periodMs: Long): State<Float> {
    val context = LocalContext.current
    val resumed by rememberIsResumed()
    val animate = remember(context) { context.animationsEnabled() }
    val phase = remember { mutableFloatStateOf(0f) }
    val period = rememberUpdatedState(periodMs)
    LaunchedEffect(resumed, animate) {
        if (!resumed || !animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        var acc = phase.floatValue
        while (true) {
            withFrameNanos { now ->
                val dt = now - last
                if (dt >= ANIMATION_FRAME_INTERVAL_NS) {
                    acc = (acc + dt / 1_000_000f / period.value) % 1f
                    phase.floatValue = acc
                    last = now
                }
            }
        }
    }
    return phase
}
