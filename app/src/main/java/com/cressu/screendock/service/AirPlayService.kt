package com.cressu.screendock.service

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import com.cressu.screendock.R
import com.cressu.screendock.bridge.NativeBridge
import com.cressu.screendock.bridge.RaopCallbackHandler
import com.cressu.screendock.discovery.NsdServiceManager
import com.cressu.screendock.model.HwAddressProvider
import com.cressu.screendock.renderer.AudioRenderer
import com.cressu.screendock.renderer.ScaleMode
import com.cressu.screendock.renderer.VideoRenderer
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.settings.PinMode
import com.cressu.screendock.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AirPlayService"

/**
 * Foreground service hosting the UxPlay native server (FR-8).
 *
 * **Threading model.** Every lifecycle operation (start / stop / restart / re-advertise /
 * shutdown) is a message on [ops], consumed by ONE coroutine, so they are strictly serialized
 * and a newer RESTART supersedes queued ones. Native teardown (which joins native threads) and
 * renderer release therefore never run on the main thread. Native callbacks arrive on UxPlay
 * pthreads and only touch thread-safe state.
 *
 * Start with [ACTION_START]/[ACTION_USER_START]; the service foregrounds itself on every
 * `onStartCommand` (required, or the OS kills the app) and stays until [ACTION_STOP].
 */
class AirPlayService : Service(), RaopCallbackHandler {

    private sealed interface Op {
        object Start : Op
        object Restart : Op
        object Readvertise : Op
        class Stop(val startId: Int, val showStoppedCard: Boolean) : Op
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val ops = Channel<Op>(Channel.UNLIMITED)
    private lateinit var lifecycleJob: Job

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var notifier: ReceiverNotification
    private lateinit var hls: HlsPlayerController
    private lateinit var limiter: LimiterCoordinator
    private val gate = ApprovalGate()

    @Volatile private var handle: Long = 0L
    @Volatile private var settingsNow = AirPlaySettings()
    @Volatile private var allowedDevices: Set<String> = emptySet()
    @Volatile private var admittedSender: String = ""
    @Volatile private var destroyed = false
    @Volatile private var fgActive = false
    @Volatile private var mdnsError = false

    private var nsdManager: NsdServiceManager? = null
    private var networkWatcher: NetworkWatcher? = null
    private var audioRenderer: AudioRenderer? = null
    private var mediaSession: MediaSessionController? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val wifiLockMutex = Any()
    private val sessionLock = Any()

    private val stateLock = Any()
    private val connCount = AtomicInteger(0)
    private val activity = AtomicLong(0)   // bumps on conn_init and first media; reset-watchdog input
    private val trackGen = AtomicInteger(0)
    @Volatile private var sessionStartElapsed = 0L
    @Volatile private var sessionAlerted = false
    @Volatile private var lastNotifKey: Any? = null

    // ---- Service lifecycle (main thread) ------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        settingsRepo = SettingsRepository(applicationContext)
        notifier = ReceiverNotification(applicationContext).also { it.createChannels() }
        activeGate = gate
        hls = HlsPlayerController(applicationContext, { activeHandle }, { onHlsFinished() })
        limiter = LimiterCoordinator(
            scope = scope, repo = settingsRepo,
            onCooldownChanged = { until -> _cooldownUntilMs.value = if (proUnlocked.value) 0L else until },
            onExhausted = ::onLimitExhausted,
        )
        scope.launch { hls.player.collect { _hlsPlayer.value = it } }
        scope.launch {
            settingsRepo.settings.collect { s ->
                settingsNow = s
                videoRenderer?.scaleMode = parseScale(s.scaleMode)
            }
        }
        scope.launch { proUnlocked.collect { limiter.publishCooldown(); updateLimiterCounting() } }
        lifecycleJob = scope.launch { runLifecycle() }
        _receiverEnabled.value = ReceiverPrefs.isEnabled(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true) fgsRestricted = true
        if (intent?.action == ACTION_USER_START) { fgsRestricted = false }
        // Every start delivery must be matched by startForeground() or the OS throws
        // ForegroundServiceDidNotStartInTimeException (even for STOP / sticky null-intent restarts).
        if (!enterForeground()) {
            if (intent?.action == ACTION_STOP) {
                setEnabled(false) // stopSelf below tears the receiver down
            }
            if (intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true) {
                runCatching { notifier.notifyBootPrompt() }
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                setEnabled(false)
                ops.trySend(Op.Stop(startId, showStoppedCard = true))
            }
            ACTION_USER_START -> {
                setEnabled(true)
                notifier.cancelStopped()
                ops.trySend(Op.Start)
            }
            ACTION_RESTART -> ops.trySend(Op.Restart)
            ACTION_APPROVE -> gate.answer(true)
            ACTION_DENY -> gate.answer(false)
            else -> { // ACTION_START and the null-intent sticky restart
                if (_receiverEnabled.value) ops.trySend(Op.Start)
                else ops.trySend(Op.Stop(startId, showStoppedCard = false))
            }
        }
        return START_STICKY
    }

    /** The product is a persistent receiver: swiping the task away keeps it running while enabled. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; enabled=${_receiverEnabled.value}")
        if (!_receiverEnabled.value) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        serviceAlive = false
        fgActive = false
        ops.close() // consumer drains, then tears everything down off the main thread
        previousShutdown = lifecycleJob
        if (activeGate === gate) activeGate = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setEnabled(enabled: Boolean) {
        ReceiverPrefs.setEnabled(applicationContext, enabled)
        _receiverEnabled.value = enabled
    }

    private fun enterForeground(): Boolean {
        val n = notifier.buildForeground(currentModel(starting = true), settingsNow.displayName, null, null, null)
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (fgsRestricted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else 0
            ServiceCompat.startForeground(this, ReceiverNotification.NOTIF_FOREGROUND, n, type)
            fgActive = true
            true
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException / missing FGS permission: never crash.
            Log.e(TAG, "startForeground failed", t)
            _errorMessage.value = getString(R.string.error_start_failed, t.javaClass.simpleName)
            false
        }
    }

    // ---- Serialized lifecycle ---------------------------------------------------------

    private suspend fun runLifecycle() {
        try {
            for (first in ops) {
                val batch = mutableListOf(first)
                while (true) batch += ops.tryReceive().getOrNull() ?: break
                batch.forEachIndexed { i, op ->
                    // A later Start/Restart/Stop makes earlier start-ish ops redundant (latest wins).
                    val superseded = op !is Op.Stop && batch.drop(i + 1).any { it !is Op.Readvertise }
                    if (!superseded && !destroyed) runOp(op)
                }
            }
        } finally {
            withContext(NonCancellable) {
                teardown()
                hls.release()
                mediaSession?.release(); mediaSession = null
                scope.cancel()
            }
        }
    }

    private suspend fun runOp(op: Op) {
        try {
            when (op) {
                Op.Start -> doStart()
                Op.Restart -> { teardown(); doStart() }
                Op.Readvertise -> doReadvertise()
                is Op.Stop -> {
                    teardown()
                    main.post { finishStop(op) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "lifecycle op failed: $op", t)
            surfaceError(t)
            runCatching { teardown() }
        }
    }

    private fun finishStop(op: Op.Stop) {
        if (_receiverEnabled.value) return // user pressed Start again while we were stopping
        fgActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (op.showStoppedCard) notifier.notifyStopped()
        stopSelf(op.startId) // no-op if a newer start command was delivered
    }

    private suspend fun doStart() {
        previousShutdown?.takeIf { it !== lifecycleJob }?.join()
        if (handle != 0L) return
        if (!NativeBridge.isLoaded()) {
            _errorMessage.value = getString(R.string.error_native_not_loaded)
            refreshAll(); return
        }
        val settings = settingsRepo.current()
        settingsNow = settings
        proUnlocked.value = proUnlocked.value || settings.proUnlocked
        limiter.load()
        allowedDevices = settingsRepo.allowedDevices()

        videoRenderer = VideoRenderer().also { it.scaleMode = parseScale(settings.scaleMode) }
        audioRenderer = AudioRenderer()

        val hwAddr = HwAddressProvider.getHardwareAddress(applicationContext)
        val keyFile = File(filesDir, "airplay_key.pem").absolutePath
        val h = NativeBridge.init(this, hwAddr, settings.displayName, keyFile, settings.pinMode != PinMode.OFF)
        if (h == 0L) throw IllegalStateException("native init failed")
        handle = h
        configureNative(h, settings)
        val port = NativeBridge.start(h, 0 /* OS-assigned */)
        if (port <= 0) throw IllegalStateException("native start failed (port=$port)")
        activeHandle = h
        Log.i(TAG, "native server bound on port $port")

        nsdManager = NsdServiceManager(applicationContext, ::onNsdStatus).also {
            it.start(
                settings.displayName, port,
                NativeBridge.getAirplayTxtRecords(h), NativeBridge.getRaopTxtRecords(h),
                NativeBridge.getRaopServiceName(h),
            )
        }
        networkWatcher = NetworkWatcher(applicationContext) { has -> onNetworkChanged(has) }.also { it.start() }

        _errorMessage.value = null
        mdnsError = false
        _state.value = ReceiverState.WAITING
        refreshAll()
        Log.i(TAG, "Receiver advertising as \"${settings.displayName}\"")
    }

    private fun configureNative(h: Long, s: AirPlaySettings) {
        NativeBridge.setH265Enabled(h, s.h265Enabled)
        val (w, hgt) = resolveAdvertisedSize(s)
        NativeBridge.setDisplaySize(h, w, hgt, s.maxFps)
        NativeBridge.setHlsEnabled(h, s.hlsEnabled)
        val pin = when (s.pinMode) {
            PinMode.FIXED -> s.fixedPin.takeIf { it.length == 4 && it.all(Char::isDigit) }
            else -> null // RANDOM (or FIXED with an invalid value): native generates one per pairing
        }
        NativeBridge.setPin(h, pin)
    }

    private suspend fun doReadvertise() {
        if (handle == 0L) return
        if (!hasLocalNetwork()) {
            _errorMessage.value = getString(R.string.error_no_network)
            refreshAll(); return
        }
        if (_errorMessage.value == getString(R.string.error_no_network)) _errorMessage.value = null
        nsdManager?.readvertise()
        refreshAll()
    }

    /** Stops native first (joins its threads), THEN releases renderers. Idempotent; IO thread only. */
    private fun teardown() {
        Log.i(TAG, "teardown")
        gate.cancelAll()
        networkWatcher?.stop(); networkWatcher = null
        nsdManager?.stop(); nsdManager = null
        val h = handle
        activeHandle = 0L
        hls.stopAndWait() // no HLS push may reach native after this point
        if (h != 0L) {
            try { NativeBridge.stop(h) } catch (t: Throwable) { Log.e(TAG, "native stop", t) }
            try { NativeBridge.destroy(h) } catch (t: Throwable) { Log.e(TAG, "native destroy", t) }
            handle = 0L
        }
        val vr = videoRenderer
        videoRenderer = null
        vr?.release()
        audioRenderer?.release(); audioRenderer = null
        connCount.set(0)
        resetSessionState(countSuccess = false)
        synchronized(stateLock) { _state.value = ReceiverState.IDLE }
        releaseWifiLock()
        limiter.setCounting(false)
        refreshAll()
    }

    private fun surfaceError(t: Throwable) {
        _errorMessage.value = getString(R.string.error_start_failed, t.message ?: t.javaClass.simpleName)
        refreshAll()
    }

    private fun onNsdStatus(code: Int?) {
        if (code == null) {
            if (mdnsError) { mdnsError = false; _errorMessage.value = null }
        } else {
            mdnsError = true
            _errorMessage.value = getString(R.string.error_mdns_failed, code)
        }
        refreshAll()
    }

    private fun onNetworkChanged(hasNetwork: Boolean) {
        if (!hasNetwork) { _errorMessage.value = getString(R.string.error_no_network); refreshAll() }
        ops.trySend(Op.Readvertise)
    }

    private fun hasLocalNetwork(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
    }

    private fun onLimitExhausted() {
        Log.i(TAG, "free session limit reached; ending session")
        _sessionLimitHit.value = true
        ops.trySend(Op.Restart) // internal: no startForegroundService from the background
    }

    /**
     * Resolve the advertised mirroring size from settings. The 0×0 "Native" sentinel maps to the
     * real panel resolution (landscape-oriented), capped at 4K.
     */
    private fun resolveAdvertisedSize(settings: AirPlaySettings): Pair<Int, Int> {
        if (settings.maxWidth > 0 && settings.maxHeight > 0) return settings.maxWidth to settings.maxHeight
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
        var pw = maxOf(w, h)
        var ph = minOf(w, h)
        if (pw > 3840) { ph = ph * 3840 / pw; pw = 3840 }
        Log.i(TAG, "Native resolution advertised: ${pw}x${ph}")
        return pw to ph
    }

    private fun parseScale(name: String): ScaleMode =
        runCatching { ScaleMode.valueOf(name) }.getOrDefault(ScaleMode.FIT)

    // ---- derived state: notification, locks, limiter, media session -----------------------

    private fun currentModel(starting: Boolean = false) = NotificationModel(
        enabled = _receiverEnabled.value,
        state = _state.value,
        mode = _mediaMode.value,
        sender = _connectedSender.value,
        trackTitle = _nowPlaying.value?.title,
        pin = _currentPin.value,
        error = _errorMessage.value,
        starting = starting,
    )

    private fun refreshAll() {
        updateWifiLock()
        updateLimiterCounting()
        updateMediaSession()
        refreshNotification()
    }

    private fun refreshNotification() {
        if (!fgActive) return
        val np = _nowPlaying.value
        val m = currentModel()
        val key = listOf(m, np?.cover, np?.artist)
        if (key == lastNotifKey) return
        lastNotifKey = key
        val n = notifier.buildForeground(m, settingsNow.displayName, np?.cover, mediaSession?.token, np?.artist)
        notifier.notifyForeground(n)
    }

    private fun updateLimiterCounting() {
        val mode = _mediaMode.value
        val count = !proUnlocked.value && connCount.get() > 0 &&
            (mode == MediaMode.MIRROR || mode == MediaMode.VIDEO)
        if (::limiter.isInitialized) limiter.setCounting(count)
    }

    private fun updateWifiLock() {
        if (connCount.get() > 0) acquireWifiLock() else releaseWifiLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        synchronized(wifiLockMutex) {
            if (wifiLock?.isHeld == true) return
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "screendock:session").apply { setReferenceCounted(false); acquire() }
            } catch (t: Throwable) { Log.w(TAG, "wifi lock failed", t) }
        }
    }

    private fun releaseWifiLock() {
        synchronized(wifiLockMutex) {
            try { wifiLock?.takeIf { it.isHeld }?.release() } catch (t: Throwable) { Log.w(TAG, "wifi unlock", t) }
            wifiLock = null
        }
    }

    private fun updateMediaSession() {
        synchronized(sessionLock) {
            val audio = _mediaMode.value == MediaMode.AUDIO && connCount.get() > 0
            if (audio && mediaSession == null) mediaSession = MediaSessionController(applicationContext)
            val ms = mediaSession ?: return
            val np = _nowPlaying.value
            if (audio && np != null) { ms.updateMetadata(np); ms.setActive(true) } else ms.setActive(false)
        }
    }

    private fun isUiForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Auto-open: full-screen-intent notification (see [ReceiverNotification.notifyAlert] for the limits). */
    private fun maybeAlertSession() {
        if (sessionAlerted || !settingsNow.autoOpenOnConnect || isUiForeground()) return
        sessionAlerted = true
        val who = admittedSender.ifBlank { getString(R.string.sender_unknown) }
        notifier.notifySessionStarted(who)
    }

    private fun setMode(mode: MediaMode) {
        synchronized(stateLock) {
            if (_mediaMode.value == mode) return
            _mediaMode.value = mode
            if (mode == MediaMode.NONE || mode == MediaMode.MIRROR) _nowPlaying.value = null
        }
        if (mode != MediaMode.NONE) { activity.incrementAndGet(); _currentPin.value = null }
        refreshAll()
    }

    /** Clear everything session-scoped. Does not touch [state]. */
    private fun resetSessionState(countSuccess: Boolean) {
        val started = sessionStartElapsed
        sessionStartElapsed = 0L
        sessionAlerted = false
        trackGen.incrementAndGet()
        if (countSuccess && started > 0L && SystemClock.elapsedRealtime() - started >= MIN_SUCCESS_SESSION_MS) {
            scope.launch { runCatching { settingsRepo.incrementSuccessfulSessions() } }
        }
        synchronized(stateLock) {
            _mediaMode.value = MediaMode.NONE
            _nowPlaying.value = null
            _currentPin.value = null
            _connectedSender.value = null
            _pendingApproval.value = null
        }
        admittedSender = ""
        if (::notifier.isInitialized) notifier.cancelAlerts()
    }

    // ---- RaopCallbackHandler (called on native pthreads) -------------------

    /* Senders open multiple RTSP connections (a probe + the real session);
     * conn_init/conn_destroy fire per connection. State must track the LAST connection. */

    override fun onClientRequest(deviceId: String, model: String, name: String): Boolean = try {
        val label = name.ifBlank { model }.ifBlank { getString(R.string.sender_unknown) }
        if (!proUnlocked.value && !limiter.canAdmit()) {
            Log.i(TAG, "refusing sender: free-tier cooldown")
            false
        } else {
            val ok = gate.request(
                deviceId, label, settingsNow.askBeforeAllow, allowedDevices,
                onPending = ::onApprovalPending, onAllowed = ::rememberDevice,
            )
            if (ok) admittedSender = label
            ok
        }
    } catch (t: Throwable) {
        Log.e(TAG, "onClientRequest failed; admitting", t)
        true
    }

    private fun onApprovalPending(label: String?) {
        _pendingApproval.value = label
        if (label != null) { if (!isUiForeground()) notifier.notifyApproval(label) } else notifier.cancelApproval()
    }

    private fun rememberDevice(deviceId: String) {
        if (deviceId.isBlank()) return
        allowedDevices = allowedDevices + deviceId
        scope.launch { runCatching { settingsRepo.addAllowedDevice(deviceId) } }
    }

    override fun onConnectionInit() {
        val n = connCount.incrementAndGet()
        activity.incrementAndGet()
        Log.i(TAG, "sender connected (conns=$n)")
        if (n == 1) {
            sessionStartElapsed = SystemClock.elapsedRealtime()
            synchronized(stateLock) {
                _state.value = ReceiverState.CONNECTED
                _connectedSender.value = admittedSender.ifBlank { getString(R.string.sender_unknown) }
            }
            // With a PIN the UI is opened once the code is known (see onDisplayPin).
            if (settingsNow.pinMode == PinMode.OFF) maybeAlertSession()
            refreshAll()
        }
    }

    override fun onConnectionDestroy() {
        val n = connCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        Log.i(TAG, "sender disconnected (conns=$n)")
        if (n == 0) endSession(countSuccess = true)
    }

    private fun endSession(countSuccess: Boolean) {
        hls.stop()
        videoRenderer?.reset()
        audioRenderer?.reset()
        resetSessionState(countSuccess)
        synchronized(stateLock) { if (handle != 0L) _state.value = ReceiverState.WAITING }
        refreshAll()
    }

    /**
     * conn_reset = the media connection died (network problem / unsupported source). UxPlay then
     * tears the HTTP connections down, so we consider the session over NOW (connCount=0) instead
     * of leaving the counter and state out of sync; a late conn_destroy is clamped at 0.
     */
    override fun onConnectionReset(reason: Int) {
        Log.w(TAG, "connection reset reason=$reason")
        connCount.set(0)
        endSession(countSuccess = false)
    }

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        val m = _mediaMode.value
        if (m != MediaMode.MIRROR && m != MediaMode.VIDEO) setMode(MediaMode.MIRROR)
        videoRenderer?.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSize(w: Float, h: Float, par: Float, rotation: Float) {
        Log.d(TAG, "video size: ${w}x${h} par=$par rot=$rotation")
        val m = _mediaMode.value
        if (m != MediaMode.MIRROR && m != MediaMode.VIDEO) setMode(MediaMode.MIRROR)
    }

    override fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int) {
        audioRenderer?.feedAudio(data, ct, ntpTimeNs, seqNum)
    }

    override fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean) {
        Log.i(TAG, "audio format: ct=$ct spf=$samplesPerFrame usingScreen=$usingScreen")
        audioRenderer?.onFormat(ct, samplesPerFrame)
        val m = _mediaMode.value
        if (m == MediaMode.NONE) setMode(if (usingScreen) MediaMode.MIRROR else MediaMode.AUDIO)
    }

    override fun onVolumeChange(volume: Float) {
        Log.i(TAG, "sender volume: $volume dB")
        audioRenderer?.setVolume(volume)
    }

    override fun onDisplayPin(pin: String) {
        _currentPin.value = pin // never logged
        maybeAlertSession()
        refreshAll()
    }

    // ---- music metadata -------------------------------------------------------------------

    override fun onAudioMetadata(title: String?, artist: String?, album: String?) {
        trackGen.incrementAndGet()
        synchronized(stateLock) {
            _nowPlaying.value = NowPlayingReducer.withMetadata(_nowPlaying.value, title, artist, album)
        }
        refreshAll()
    }

    override fun onCoverArt(data: ByteArray) {
        val gen = trackGen.get()
        scope.launch {
            val bmp = CoverArtDecoder.decode(data) ?: return@launch
            synchronized(stateLock) {
                if (trackGen.get() != gen) return@launch // a newer track arrived while decoding
                _nowPlaying.value = NowPlayingReducer.withCover(_nowPlaying.value, bmp)
            }
            refreshAll()
        }
    }

    override fun onAudioProgress(positionSec: Float, durationSec: Float) {
        val np = synchronized(stateLock) {
            NowPlayingReducer.withProgress(_nowPlaying.value, positionSec, durationSec).also { _nowPlaying.value = it }
        }
        mediaSession?.updateProgress(np.positionSec)
    }

    // ---- AirPlay video (HLS) ------------------------------------------------------------------

    override fun onHlsPlay(location: String, startPositionSec: Float) {
        Log.i(TAG, "HLS play")
        if (!proUnlocked.value && !limiter.canAdmit()) {
            Log.i(TAG, "refusing HLS play: free-tier cooldown")
            NativeBridge.updateHlsPlaybackInfo(activeHandle, -1.0, 0.0, 0f, false)
            return
        }
        hls.play(location, startPositionSec)
        setMode(MediaMode.VIDEO)
    }

    override fun onHlsStop() {
        hls.stop()
        if (_mediaMode.value == MediaMode.VIDEO) setMode(MediaMode.NONE)
    }

    override fun onHlsScrub(positionSec: Float) = hls.scrub(positionSec)
    override fun onHlsRate(rate: Float) = hls.setRate(rate)

    private fun onHlsFinished() {
        if (_mediaMode.value == MediaMode.VIDEO) setMode(MediaMode.NONE)
    }

    companion object {
        const val ACTION_START = "com.cressu.screendock.START"
        const val ACTION_STOP = "com.cressu.screendock.STOP"
        const val ACTION_RESTART = "com.cressu.screendock.RESTART"
        /** User-initiated Start (re-enables the receiver after a Stop). */
        const val ACTION_USER_START = "com.cressu.screendock.USER_START"
        const val ACTION_APPROVE = "com.cressu.screendock.APPROVE"
        const val ACTION_DENY = "com.cressu.screendock.DENY"
        const val EXTRA_FROM_BOOT = "from_boot"

        /** Free-tier mirroring/video minutes per usage window (Pro = unlimited). */
        const val FREE_SESSION_MINUTES = 10L

        private const val MIN_SUCCESS_SESSION_MS = 60_000L

        private val _state = MutableStateFlow(ReceiverState.IDLE)
        val state: StateFlow<ReceiverState> = _state

        private val _mediaMode = MutableStateFlow(MediaMode.NONE)
        val mediaMode: StateFlow<MediaMode> = _mediaMode

        private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
        val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

        private val _cooldownUntilMs = MutableStateFlow(0L)
        /** Free tier: new connections are refused until this wall-clock time (0 = not cooling down). */
        val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs

        private val _connectedSender = MutableStateFlow<String?>(null)
        val connectedSender: StateFlow<String?> = _connectedSender

        private val _pendingApproval = MutableStateFlow<String?>(null)
        /** Sender awaiting an "ask before allowing" answer; reply with [answerApproval]. */
        val pendingApproval: StateFlow<String?> = _pendingApproval

        private val _hlsPlayer = MutableStateFlow<Player?>(null)
        /** Non-null while [MediaMode.VIDEO]; render with media3 PlayerView. The service owns its lifecycle. */
        val hlsPlayer: StateFlow<Player?> = _hlsPlayer

        private val _receiverEnabled = MutableStateFlow(true)
        /** User intent: false after Stop until Start. Refreshed from storage by [start]. */
        val receiverEnabled: StateFlow<Boolean> = _receiverEnabled

        private val _currentPin = MutableStateFlow<String?>(null)
        /** PIN to display while a sender is pairing. */
        val currentPin: StateFlow<String?> = _currentPin

        private val _errorMessage = MutableStateFlow<String?>(null)
        /** User-presentable start/mDNS/network error, null when healthy. */
        val errorMessage: StateFlow<String?> = _errorMessage

        /** ScreenDock Pro entitlement — set from Billing/DataStore, read by the session limiter. */
        val proUnlocked = MutableStateFlow(false)

        private val _sessionLimitHit = MutableStateFlow(false)
        /** True right after a session is ended by the free-tier limit. */
        val sessionLimitHit: StateFlow<Boolean> = _sessionLimitHit
        fun clearSessionLimitHit() { _sessionLimitHit.value = false }

        /** Exposed for the Activity to forward the display Surface to the decoder pipeline. */
        @Volatile var videoRenderer: VideoRenderer? = null

        @Volatile private var activeHandle: Long = 0L
        @Volatile private var serviceAlive = false
        @Volatile private var fgsRestricted = false
        @Volatile private var activeGate: ApprovalGate? = null
        @Volatile private var previousShutdown: Job? = null

        /** Inject a touch event into the connected sender (no-op when idle). */
        fun sendTouch(phase: Int, xNorm: Double, yNorm: Double) {
            val h = activeHandle
            if (h != 0L) NativeBridge.sendTouchEvent(h, phase, xNorm, yNorm)
        }

        /** Answer the pending "ask before allowing" prompt. */
        fun answerApproval(allow: Boolean) { activeGate?.answer(allow) }

        /** Start (from the Activity). Respects an explicit user Stop. Never throws. */
        fun start(context: Context) {
            val app = context.applicationContext
            val enabled = ReceiverPrefs.isEnabled(app)
            _receiverEnabled.value = enabled
            if (!enabled) return
            fgsRestricted = false // a visible Activity lifts the boot-start FGS-type restriction
            launch(app, ACTION_START)
        }

        /** User tapped Start: re-enables the receiver. */
        fun requestStart(context: Context) {
            val app = context.applicationContext
            ReceiverPrefs.setEnabled(app, true)
            _receiverEnabled.value = true
            fgsRestricted = false
            launch(app, ACTION_USER_START)
        }

        /** User tapped Stop: stops the service and advertising; stays stopped across launches/boots. */
        fun requestStop(context: Context) {
            val app = context.applicationContext
            ReceiverPrefs.setEnabled(app, false)
            _receiverEnabled.value = false
            if (serviceAlive) {
                runCatching { app.startService(Intent(app, AirPlayService::class.java).setAction(ACTION_STOP)) }
                    .onFailure { Log.w(TAG, "stop delivery failed", it) }
            }
        }

        @Deprecated("Use requestStop", ReplaceWith("requestStop(context)"))
        fun stop(context: Context) = requestStop(context)

        /** Restart with fresh settings (Settings → Apply). No-op while the user has stopped the receiver. */
        fun restart(context: Context) {
            val app = context.applicationContext
            if (!ReceiverPrefs.isEnabled(app)) return
            launch(app, ACTION_RESTART)
        }

        /** Boot autostart (see [BootReceiver] for the Android 15 policy). */
        internal fun startFromBoot(context: Context) {
            fgsRestricted = true
            val app = context.applicationContext
            val i = Intent(app, AirPlayService::class.java).setAction(ACTION_START).putExtra(EXTRA_FROM_BOOT, true)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
            } catch (t: Throwable) {
                Log.w(TAG, "boot start rejected; posting prompt", t)
                ReceiverNotification(app).apply { createChannels() }.notifyBootPrompt()
            }
        }

        private fun launch(app: Context, action: String) {
            val i = Intent(app, AirPlayService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
            } catch (t: Throwable) { // e.g. ForegroundServiceStartNotAllowedException from the background
                Log.e(TAG, "cannot start service ($action)", t)
                _errorMessage.value = app.getString(R.string.error_start_failed, t.javaClass.simpleName)
            }
        }
    }
}
