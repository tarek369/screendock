package com.cressu.screendock.ui

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cressu.screendock.bridge.NativeBridge
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.service.MediaMode
import com.cressu.screendock.ui.theme.AirplayTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AirplayMainActivity"
private const val PIP_POLL_MS = 300L

class MainActivity : ComponentActivity() {

    private lateinit var pip: PipState
    private var lastPipKey: Long = Long.MIN_VALUE

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

        pip = PipState(supported = pipSupported(), enter = ::enterPip)
        startPipParamsSync()

        setContent {
            AirplayTheme {
                CompositionLocalProvider(LocalPip provides pip) {
                    AppScreen(version = version, context = applicationContext)
                }
            }
        }

        if (NativeBridge.isLoaded() && NativeBridge.selfTest()) {
            AirPlayService.start(this)
        } else {
            Log.e(TAG, "native selfTest failed; not starting service")
        }
    }

    // --- Picture-in-picture (approach adapted from jqssun/android-airplay-server, GPL-3.0) ---

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun shouldEnterPip(): Boolean = PipLogic.shouldEnter(
        mode = AirPlayService.mediaMode.value,
        mediaScreenShowing = pip.mediaScreenShowing,
        isTelevision = isTelevision(),
        pipSupported = pip.supported,
    )

    private fun currentVideoAspectMilli(): Int {
        val size = when (AirPlayService.mediaMode.value) {
            MediaMode.VIDEO -> AirPlayService.hlsPlayer.value?.videoSize?.let { it.width to it.height }
            else -> AirPlayService.videoRenderer?.videoSize?.value?.let { it.width to it.height }
        }
        return PipLogic.aspectMilli(size?.first ?: 0, size?.second ?: 0)
    }

    private fun buildPipParams(autoEnter: Boolean, aspectMilli: Int): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspectMilli, PipLogic.ASPECT_DENOMINATOR))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(false) // video surface, not a resizable UI
        }
        return builder.build()
    }

    /**
     * Keeps the PiP params (aspect ratio + API 31 auto-enter flag) in sync
     * with the current media state. State lives in several flows (mode,
     * renderer size, HLS player size), so a cheap poll while STARTED is the
     * simplest correct way; params are only pushed when they change.
     */
    private fun startPipParamsSync() {
        if (!pip.supported) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                lastPipKey = Long.MIN_VALUE
                while (true) {
                    val auto = shouldEnterPip()
                    val aspect = currentVideoAspectMilli()
                    val key = aspect.toLong() * 2 + if (auto) 1 else 0
                    if (key != lastPipKey) {
                        lastPipKey = key
                        runCatching { setPictureInPictureParams(buildPipParams(auto, aspect)) }
                            .onFailure { Log.w(TAG, "setPictureInPictureParams failed", it) }
                    }
                    delay(PIP_POLL_MS)
                }
            }
        }
    }

    private fun enterPip() {
        if (!pip.supported || !shouldEnterPip()) return
        runCatching { enterPictureInPictureMode(buildPipParams(true, currentVideoAspectMilli())) }
            .onFailure { Log.w(TAG, "enterPictureInPictureMode failed", it) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ auto-enters via params; 26..30 need the explicit call.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::pip.isInitialized) pip.isInPip = isInPictureInPictureMode
    }
}
