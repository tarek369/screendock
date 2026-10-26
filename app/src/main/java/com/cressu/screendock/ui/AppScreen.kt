package com.cressu.screendock.ui

import com.cressu.screendock.billing.PurchaseUiState

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cressu.screendock.bridge.NativeBridge
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.service.MediaMode
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.settings.SettingsRepository
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AirplayMainActivity"
private const val REVIEW_SETTLE_DELAY_MS = 1_500L

private enum class Screen { IDLE, SETTINGS, TROUBLESHOOTING }

@Composable
fun AppScreen(version: String, context: Context) {
    var screen by remember { mutableStateOf(Screen.IDLE) }
    var troubleshootingReturn by remember { mutableStateOf(Screen.IDLE) }
    var connectHelpDismissed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repo = remember(context) { SettingsRepository(context) }
    // null until DataStore has produced the first value (avoids flashing onboarding).
    val loadedSettings by repo.settings.collectAsState(initial = null)
    var selfTestOk by remember { mutableStateOf<Boolean?>(null) }

    val state by AirPlayService.state.collectAsState()
    val mediaMode by AirPlayService.mediaMode.collectAsState()
    val nowPlaying by AirPlayService.nowPlaying.collectAsState()
    val hlsPlayer by AirPlayService.hlsPlayer.collectAsState()
    val cooldownUntilMs by AirPlayService.cooldownUntilMs.collectAsState()
    val connectedSender by AirPlayService.connectedSender.collectAsState()
    val pendingApproval by AirPlayService.pendingApproval.collectAsState()
    val currentPin by AirPlayService.currentPin.collectAsState()
    val receiverEnabled by AirPlayService.receiverEnabled.collectAsState()
    val proUnlocked by AirPlayService.proUnlocked.collectAsState()
    val sessionLimitHit by AirPlayService.sessionLimitHit.collectAsState()

    // --- Billing: process-wide manager, refreshed on resume, released on dispose ---
    val billing = remember(context) { BillingHolder.get(context, repo) }
    val formattedPrice by billing.formattedPrice.collectAsState()
    val purchaseState by billing.purchaseState.collectAsState()
    val pro = ProUiState(proUnlocked, formattedPrice, purchaseState)

    LaunchedEffect(Unit) {
        selfTestOk = if (NativeBridge.isLoaded()) NativeBridge.selfTest() else false
        if (selfTestOk == true) billing.start()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, billing) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) billing.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            billing.endConnection()
        }
    }

    val activityContext = LocalContext.current
    val buyPro: () -> Unit = {
        val activity = activityContext.findActivity()
        if (activity != null) billing.launchPurchase(activity) { }
        else Log.w(TAG, "purchase unavailable: no activity")
    }

    // --- Notification permission (Android 13+): one-shot ask after onboarding ---
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS " + if (granted) "granted" else "denied")
    }
    val onboardingDone = loadedSettings?.onboardingDone
    LaunchedEffect(onboardingDone) {
        // Onboarding page 3 asks explicitly; existing installs get the legacy one-shot ask.
        if (onboardingDone != true || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val activity = activityContext.findActivity() ?: return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return@LaunchedEffect
        val prefs = activity.getSharedPreferences("ui_state", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_asked", false)) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- Navigation ---
    BackHandler(enabled = screen != Screen.IDLE) {
        screen = if (screen == Screen.TROUBLESHOOTING) troubleshootingReturn else Screen.IDLE
    }
    // A connection starting while the user is in an inner page brings them back to the media screen.
    LaunchedEffect(mediaMode) {
        if (mediaMode != MediaMode.NONE && loadedSettings?.autoOpenOnConnect != false) screen = Screen.IDLE
    }

    // Effective mode only counts while the media screen is actually showing.
    val shownMode = if (screen == Screen.IDLE) mediaMode else MediaMode.NONE
    WindowModeEffect(shownMode, loadedSettings?.keepScreenOnAudio == true)

    // --- In-app review (only on idle, right after a session ended) ---
    var previousMode by remember { mutableStateOf(mediaMode) }
    var sessionJustEnded by remember { mutableStateOf(false) }
    LaunchedEffect(mediaMode) {
        if (previousMode != MediaMode.NONE && mediaMode == MediaMode.NONE) sessionJustEnded = true
        if (mediaMode != MediaMode.NONE) sessionJustEnded = false
        previousMode = mediaMode
    }
    LaunchedEffect(sessionJustEnded, screen) {
        if (!sessionJustEnded || screen != Screen.IDLE) return@LaunchedEffect
        delay(REVIEW_SETTLE_DELAY_MS) // let the service record the finished session
        val s = repo.current()
        val eligible = ReviewPolicy.shouldRequest(
            successfulSessions = s.successfulSessions,
            reviewPrompted = s.reviewPrompted,
            sessionJustEnded = true,
            mediaActive = AirPlayService.mediaMode.value != MediaMode.NONE,
            paywallOrCooldown = AirPlayService.sessionLimitHit.value ||
                AirPlayService.cooldownUntilMs.value > System.currentTimeMillis(),
        )
        sessionJustEnded = false
        val activity = activityContext.findActivity()
        if (eligible && activity != null) {
            repo.updateReviewPrompted(true) // once ever, regardless of what Play decides to show
            requestInAppReview(activity)
        }
    }

    // --- In-app review right after an in-app Pro purchase ---
    var wasPurchased by remember { mutableStateOf(purchaseState is PurchaseUiState.Purchased) }
    LaunchedEffect(purchaseState) {
        val isPurchased = purchaseState is PurchaseUiState.Purchased
        val ask = ReviewPolicy.shouldRequestAfterPurchase(
            wasPro = wasPurchased,
            isPro = isPurchased,
            reviewPrompted = repo.current().reviewPrompted,
        )
        wasPurchased = isPurchased
        val activity = activityContext.findActivity()
        if (ask && activity != null) {
            delay(REVIEW_SETTLE_DELAY_MS) // let the Play purchase sheet close first
            repo.updateReviewPrompted(true)
            requestInAppReview(activity)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Palette.bg) {
        val settings = loadedSettings
        if (settings == null) return@Surface

        Box(Modifier.fillMaxSize()) {
            if (!settings.onboardingDone && mediaMode == MediaMode.NONE) {
                OnboardingScreen(
                    deviceName = settings.displayName.ifBlank { "ScreenDock" },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onDone = { scope.launch { repo.updateOnboardingDone(true) } },
                )
            } else {
                NavHost(
                    screen = screen,
                    mediaMode = mediaMode,
                ) { current, mode ->
                    when (current) {
                        Screen.SETTINGS -> SettingsScreen(
                            repo = repo,
                            pro = pro,
                            onApply = {
                                // Single atomic restart command — stop()+start() races
                                // stopSelf() against the pending start.
                                AirPlayService.restart(context)
                                screen = Screen.IDLE
                            },
                            onBack = { screen = Screen.IDLE },
                            onBuyPro = buyPro,
                            onOpenTroubleshooting = {
                                troubleshootingReturn = Screen.SETTINGS
                                screen = Screen.TROUBLESHOOTING
                            },
                            onToggleReceiver = { on ->
                                if (on) AirPlayService.requestStart(context) else AirPlayService.requestStop(context)
                            },
                        )
                        Screen.TROUBLESHOOTING -> {
                            val receiverRunning =
                                receiverEnabled && state != com.cressu.screendock.service.ReceiverState.IDLE
                            TroubleshootingScreen(
                                deviceName = settings.displayName.ifBlank { "ScreenDock" },
                                receiverRunning = receiverRunning,
                                diagnostics = { net ->
                                    supportDiagnostics(context, proUnlocked, settings, receiverRunning, net)
                                },
                                onBack = { screen = troubleshootingReturn },
                            )
                        }
                        Screen.IDLE -> MediaContent(
                            mode = mode,
                            settings = settings,
                            repo = repo,
                            nowPlaying = nowPlaying,
                            hlsPlayer = hlsPlayer,
                            connectedSender = connectedSender,
                            idle = IdleUiState(
                                version = version,
                                state = state,
                                receiverEnabled = receiverEnabled,
                                settings = settings,
                                selfTestOk = selfTestOk,
                                pro = pro,
                                sessionLimitHit = sessionLimitHit,
                                cooldownUntilMs = cooldownUntilMs,
                                connectHelpDismissed = connectHelpDismissed,
                            ),
                            idleActions = IdleActions(
                                onOpenSettings = { screen = Screen.SETTINGS },
                                onOpenHelp = {
                                    troubleshootingReturn = Screen.IDLE
                                    screen = Screen.TROUBLESHOOTING
                                },
                                onBuyPro = buyPro,
                                onDismissNudge = { AirPlayService.clearSessionLimitHit() },
                                onStartReceiver = { AirPlayService.requestStart(context) },
                                onDismissConnectHelp = { connectHelpDismissed = true },
                            ),
                        )
                    }
                }
            }

            // Pairing PIN and approval prompts sit above every screen and mode.
            currentPin?.let { PinOverlay(pin = it) }
            pendingApproval?.let { name ->
                ApprovalDialog(senderName = name, onAnswer = { AirPlayService.answerApproval(it) })
            }
        }
    }
}

@Composable
private fun NavHost(
    screen: Screen,
    mediaMode: MediaMode,
    content: @Composable (Screen, MediaMode) -> Unit,
) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState != Screen.IDLE) {
                (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it / 8 } + fadeOut(tween(180)))
            } else {
                (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(200)) { it / 8 } + fadeOut(tween(180)))
            }
        },
        label = "screen",
    ) { current -> content(current, mediaMode) }
}

@Composable
private fun MediaContent(
    mode: MediaMode,
    settings: AirPlaySettings,
    repo: SettingsRepository,
    nowPlaying: com.cressu.screendock.service.NowPlaying?,
    hlsPlayer: androidx.media3.common.Player?,
    connectedSender: String?,
    idle: IdleUiState,
    idleActions: IdleActions,
) {
    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            (fadeIn(tween(320)) + scaleIn(initialScale = 0.96f, animationSpec = tween(320))) togetherWith
                fadeOut(tween(200))
        },
        label = "mediaMode",
    ) { m ->
        when (m) {
            MediaMode.MIRROR -> RenderScreen(repo = repo, savedScaleMode = settings.scaleMode)
            MediaMode.AUDIO -> NowPlayingScreen(nowPlaying = nowPlaying, senderName = connectedSender)
            MediaMode.VIDEO -> VideoScreen(player = hlsPlayer)
            MediaMode.NONE -> IdleScreen(ui = idle, actions = idleActions)
        }
    }
}

/**
 * Mirror / video: pure cinema (bars hidden, screen kept on). Audio: bars
 * visible, screen kept on only if the user opted in. Idle: normal.
 */
@Composable
private fun WindowModeEffect(mode: MediaMode, keepScreenOnAudio: Boolean) {
    val view = LocalView.current
    LaunchedEffect(mode, keepScreenOnAudio) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val immersive = mode == MediaMode.MIRROR || mode == MediaMode.VIDEO
        val keepOn = immersive || (mode == MediaMode.AUDIO && keepScreenOnAudio)
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
