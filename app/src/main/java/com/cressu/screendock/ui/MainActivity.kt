package com.cressu.screendock.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
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
import com.cressu.screendock.service.ReceiverState
import com.cressu.screendock.settings.SettingsRepository
import com.cressu.screendock.ui.theme.AirplayTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AirplayMainActivity"
private const val PIP_POLL_MS = 300L

class MainActivity : ComponentActivity() {

    private lateinit var pip: PipState
    private var lastPipKey: Long = Long.MIN_VALUE

    private val returnTracker = ReturnToPreviousTracker()
    private val returnHandler = Handler(Looper.getMainLooper())
    @Volatile private var returnSettingOn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: swaps the launch splash theme for
        // postSplashScreenTheme (Theme.Airplay) at the right moment.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        com.cressu.screendock.logs.LogCapture.start()
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
        returnTracker.onIntent(isSummon(intent))
        startReturnToPreviousWatch()

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        returnTracker.onIntent(isSummon(intent))
    }

    override fun onStart() {
        super.onStart()
        isOnScreen = true
    }

    override fun onResume() {
        super.onResume()
        returnTracker.onResume()
        if (!returnTracker.returnPending) returnHandler.removeCallbacksAndMessages(null)
    }

    override fun onStop() {
        super.onStop()
        isOnScreen = false
        returnTracker.onStop()
    }

    override fun onDestroy() {
        returnHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- Return to the previous app (adapted from jqssun/android-airplay-server PR #64, GPL-3.0) ---

    private fun isSummon(i: Intent?): Boolean = i?.getBooleanExtra(EXTRA_SESSION_SUMMON, false) == true

    private fun startReturnToPreviousWatch() {
        lifecycleScope.launch {
            SettingsRepository(applicationContext).settings.collect { returnSettingOn = it.returnToPreviousApp }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previous: ReceiverState? = null
                AirPlayService.state.collect { st ->
                    val was = previous
                    previous = st
                    val connected = st == ReceiverState.CONNECTED
                    when {
                        connected -> {
                            returnTracker.onSessionStarted()
                            returnHandler.removeCallbacksAndMessages(null)
                        }
                        was == ReceiverState.CONNECTED && returnTracker.onSessionEnded(returnSettingOn) -> {
                            Log.i(TAG, "session ended: returning to previous app in ${ReturnToPreviousTracker.GRACE_MS} ms")
                            returnHandler.removeCallbacksAndMessages(null)
                            returnHandler.postDelayed(::maybeReturnToPrevious, ReturnToPreviousTracker.GRACE_MS)
                        }
                    }
                }
            }
        }
    }

    private fun maybeReturnToPrevious() {
        if (isFinishing || isDestroyed) return
        val connected = AirPlayService.state.value == ReceiverState.CONNECTED
        if (returnTracker.consumeReturn(connected, returnSettingOn)) {
            Log.i(TAG, "moving task to back (previous app returns)")
            moveTaskToBack(true)
        }
    }

    // --- TV remote / keyboard keys on the AirPlay video screen (adapted from PR #16, GPL-3.0) ---

    /**
     * Activity-level dispatch: on TV nothing in the video screen holds focus (PlayerView has no
     * controller), so keys reach the window here before Compose. Matching UP events are consumed
     * too. Back is not handled here (normal navigation).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleVideoKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleVideoKey(event: KeyEvent): Boolean {
        if (AirPlayService.mediaMode.value != MediaMode.VIDEO) return false
        if (::pip.isInitialized && (pip.isInPip || !pip.mediaScreenShowing)) return false
        val player = AirPlayService.hlsPlayer.value ?: return false
        val action = VideoKeys.actionFor(event.keyCode) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0 && !VideoKeys.repeats(action)) return true
        when (action) {
            VideoKeyAction.TOGGLE -> player.playWhenReady = !player.playWhenReady
            VideoKeyAction.PLAY -> player.playWhenReady = true
            VideoKeyAction.PAUSE -> player.playWhenReady = false
            VideoKeyAction.SEEK_BACK, VideoKeyAction.SEEK_FORWARD ->
                player.seekTo(VideoKeys.seekTarget(player.currentPosition, player.duration, action))
        }
        return true
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

    companion object {
        /** Set on intents that open the UI because a session started (not a manual open). */
        const val EXTRA_SESSION_SUMMON = "com.cressu.screendock.SESSION_SUMMON"

        /** True while the activity is started (visible, incl. picture-in-picture). */
        @Volatile var isOnScreen: Boolean = false
            private set

        fun summonIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_SESSION_SUMMON, true)
    }
}
