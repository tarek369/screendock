package com.cressu.screendock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cressu.screendock.bridge.NativeBridge
import com.cressu.screendock.bridge.RaopCallbackHandler
import com.cressu.screendock.discovery.NsdServiceManager
import com.cressu.screendock.model.HwAddressProvider
import com.cressu.screendock.renderer.AudioRenderer
import com.cressu.screendock.renderer.VideoRenderer
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.settings.SettingsRepository
import com.cressu.screendock.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "AirPlayService"
private const val CHANNEL_ID = "airplay_receiver"
private const val NOTIF_ID = 1

/** Receiver states surfaced to the UI. */
enum class ReceiverState { IDLE, WAITING, CONNECTED }

/**
 * Foreground service hosting the UxPlay native server (FR-8).
 *
 * Owns the [NativeBridge] handle, implements [RaopCallbackHandler] (native
 * callbacks run on UxPlay pthreads — methods here must be thread-safe), and
 * wires decoded frames into [VideoRenderer] / [AudioRenderer]. mDNS advertising
 * is delegated to [NsdServiceManager].
 *
 * Start with [ACTION_START]; the service foregrounds itself and stays alive
 * until [ACTION_STOP] or the OS kills it (the persistent notification is the
 * user-facing keep-alive signal).
 */
class AirPlayService : Service(), RaopCallbackHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepo: SettingsRepository

    private var handle: Long = 0L
    private var nsdManager: NsdServiceManager? = null
    private var audioRenderer: AudioRenderer? = null
    private val startInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _senderName = MutableStateFlow("")
    val senderName: StateFlow<String> = _senderName

    /**
     * Direct reference for the Activity to reach the video renderer without a
     * full service binder. Set/cleared in startReceiver/stopReceiver. Safe for
     * single-process use (the foreground service and Activity share a process).
     * Merged with the lifecycle constants/methods below.
     */
    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start delivery (START, STOP, sticky restart with null intent) must
        // be matched by startForeground() before the service stops, or the system
        // throws ForegroundServiceDidNotStartInTimeException.
        startForeground(NOTIF_ID, buildNotification(
            if (_state.value == ReceiverState.CONNECTED) "Mirroring active" else "Starting…"))
        when (intent?.action) {
            ACTION_STOP -> {
                stopReceiver()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RESTART -> {
                // Atomic restart: tearing down and bringing the receiver back
                // inside one command avoids the stopSelf()-vs-pending-start race
                // (stopSelf() kills the service even after a queued ACTION_START
                // was delivered, dead-locking the UI on "Starting receiver…").
                stopReceiver()
                startReceiver()
            }
            else -> startReceiver()   // ACTION_START + null-intent sticky restart
        }
        return START_STICKY
    }

    private fun startReceiver() {
        if (!NativeBridge.isLoaded()) {
            Log.e(TAG, "Native library not loaded; cannot start receiver")
            updateNotification("Native library failed to load")
            return
        }
        if (handle != 0L) { Log.w(TAG, "already running"); return }
        // Serialize async starts: a second Apply while the first coroutine is
        // still initializing would otherwise leak a native server (handle==0
        // makes stopReceiver a no-op until init completes).
        if (!startInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "start already in flight"); return
        }
        scope.launch {
            try {
                val settings = settingsRepo.current()
                // Offline entitlement cache — Billing refreshes this when online.
                proUnlocked.value = settings.proUnlocked
                val hwAddr = HwAddressProvider.getHardwareAddress(applicationContext)
                val keyFile = File(filesDir, "airplay_key.pem").absolutePath

                handle = NativeBridge.init(this@AirPlayService, hwAddr, settings.displayName, keyFile, false)
                if (handle == 0L) {
                    Log.e(TAG, "nativeInit failed")
                    updateNotification("Failed to start")
                    return@launch
                }

                NativeBridge.setH265Enabled(handle, settings.h265Enabled)
                val (advW, advH) = resolveAdvertisedSize(settings)
                NativeBridge.setDisplaySize(handle, advW, advH, settings.maxFps)

                val port = NativeBridge.start(handle, 0 /* OS-assigned */)
                if (port <= 0) {
                    Log.e(TAG, "nativeStart failed (port=$port)")
                    updateNotification("Failed to start server")
                    NativeBridge.destroy(handle); handle = 0L
                    return@launch
                }
                Log.i(TAG, "native server bound on port $port")
            activeHandle = handle

                // Wire mDNS advertising via NsdManager (TXT records from the native shim).
                val txtAirplay = NativeBridge.getAirplayTxtRecords(handle)
                val txtRaop = NativeBridge.getRaopTxtRecords(handle)
                val raopName = NativeBridge.getRaopServiceName(handle)
                nsdManager = NsdServiceManager(applicationContext)
                nsdManager?.start(settings.displayName, port, txtAirplay, txtRaop, raopName)

                // Lazy-init renderers (they own their threads/lifecycles).
                // videoRenderer lives in the companion so the Activity can forward the display Surface.
                videoRenderer = VideoRenderer()
                audioRenderer = AudioRenderer()

                _state.value = ReceiverState.WAITING
                updateNotification("Ready to receive: ${settings.displayName}")
                Log.i(TAG, "Receiver advertising as \"${settings.displayName}\" on port $port")
            } finally {
                startInFlight.set(false)
            }
        }
    }

    private fun stopReceiver() {
        Log.i(TAG, "stopReceiver")
        connCount.set(0)
        activeHandle = 0L
        nsdManager?.stop(); nsdManager = null
        videoRenderer?.release(); videoRenderer = null
        audioRenderer?.release(); audioRenderer = null
        if (handle != 0L) {
            NativeBridge.stop(handle)
            NativeBridge.destroy(handle)
            handle = 0L
        }
        _state.value = ReceiverState.IDLE
        _senderName.value = ""
    }

    /**
     * Resolve the advertised mirroring size from settings. The 0×0 "Native"
     * sentinel maps to the real panel resolution (landscape-oriented so the
     * receiver looks TV-like to senders regardless of current rotation),
     * capped at 4K so we never ask for more than panels/senders sensibly do.
     */
    private fun resolveAdvertisedSize(settings: AirPlaySettings): Pair<Int, Int> {
        if (settings.maxWidth > 0 && settings.maxHeight > 0) {
            return settings.maxWidth to settings.maxHeight
        }
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

    // ---- RaopCallbackHandler (called on native pthreads) -------------------

    /* Senders open multiple RTSP connections (a probe + the real session);
     * conn_init/conn_destroy fire per connection. State and renderer resets
     * must track the LAST connection, not each individual one — a probe's
     * destroy after the real session's init used to flip the UI back to
     * "Ready to receive" (no render Surface ever bound → black screen) and
     * spuriously reset the live decoders. */
    private val connCount = java.util.concurrent.atomic.AtomicInteger(0)

    private var sessionLimitJob: Job? = null

    override fun onConnectionInit() {
        val n = connCount.incrementAndGet()
        Log.i(TAG, "sender connected (conns=$n)")
        if (n == 1) {
            _state.value = ReceiverState.CONNECTED
            updateNotification("Mirroring active")
            armSessionLimit()
        }
    }

    /**
     * Free tier: mirroring sessions end after [FREE_SESSION_MINUTES] minutes.
     * Pro users skip this entirely. On expiry the receiver restarts (proven
     * path — same as Settings → Apply), which drops the sender's session, and
     * the idle screen shows the upgrade nudge.
     */
    private fun armSessionLimit() {
        sessionLimitJob?.cancel()
        if (proUnlocked.value) return
        sessionLimitJob = scope.launch {
            delay(FREE_SESSION_MINUTES * 60_000L)
            if (_state.value == ReceiverState.CONNECTED && !proUnlocked.value) {
                Log.i(TAG, "free session limit reached; ending session")
                _sessionLimitHit.value = true
                val i = Intent(this@AirPlayService, AirPlayService::class.java).setAction(ACTION_RESTART)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            }
        }
    }

    override fun onConnectionDestroy() {
        val n = connCount.decrementAndGet()
        Log.i(TAG, "sender disconnected (conns=$n)")
        if (n <= 0) {
            connCount.set(0)
            sessionLimitJob?.cancel()
            _state.value = ReceiverState.WAITING
            _senderName.value = ""
            videoRenderer?.reset()
            audioRenderer?.reset()
            updateNotification("Ready to receive")
        }
    }

    override fun onConnectionReset(reason: Int) {
        Log.w(TAG, "connection reset reason=$reason")
        videoRenderer?.reset()
        audioRenderer?.reset()
        _state.value = ReceiverState.WAITING
    }

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer?.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSize(w: Float, h: Float, par: Float, rotation: Float) {
        Log.d(TAG, "video size: ${w}x${h} par=$par rot=$rotation")
        // VideoPipeline handles aspect-ratio letterboxing internally.
    }

    override fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int) {
        audioRenderer?.feedAudio(data, ct, ntpTimeNs, seqNum)
    }

    override fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean) {
        Log.i(TAG, "audio format: ct=$ct spf=$samplesPerFrame usingScreen=$usingScreen")
        audioRenderer?.onFormat(ct, samplesPerFrame)
    }

    override fun onVolumeChange(volume: Float) {
        Log.i(TAG, "sender volume: $volume dB")
        audioRenderer?.setVolume(volume)
    }

    override fun onDisplayPin(pin: String) {
        Log.i(TAG, "PIN requested (v2 feature): $pin")
        // v1 is an open receiver — PIN UI is deferred to v2.
    }

    // ---- notification ------------------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "AirPlay Receiver", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active AirPlay reception status" })
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenDock")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopReceiver()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.cressu.screendock.START"
        const val ACTION_STOP = "com.cressu.screendock.STOP"
        const val ACTION_RESTART = "com.cressu.screendock.RESTART"

        /** Free-tier session length in minutes (Pro = unlimited). */
        const val FREE_SESSION_MINUTES = 10L

        private val _state = MutableStateFlow(ReceiverState.IDLE)
        val state: StateFlow<ReceiverState> = _state

        /** ScreenDock Pro entitlement — set from Billing/DataStore, read by the session limiter. */
        val proUnlocked = MutableStateFlow(false)

        /** True right after a mirroring session is ended by the free-tier limit. */
        private val _sessionLimitHit = MutableStateFlow(false)
        val sessionLimitHit: StateFlow<Boolean> = _sessionLimitHit
        fun clearSessionLimitHit() { _sessionLimitHit.value = false }

        /** Exposed for the Activity to forward the display Surface to the decoder pipeline. */
        @Volatile var videoRenderer: VideoRenderer? = null

        /** Active native handle for touch-event injection (set once the server starts). */
        @Volatile private var activeHandle: Long = 0L

        /** Inject a touch event into the connected sender (no-op when idle). */
        fun sendTouch(phase: Int, xNorm: Double, yNorm: Double) {
            val h = activeHandle
            if (h != 0L) NativeBridge.sendTouchEvent(h, phase, xNorm, yNorm)
        }

        fun start(context: Context) {
            val i = Intent(context, AirPlayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i) else context.startService(i)
        }

        /** Restart the receiver with fresh settings (used by Settings → Apply). */
        fun restart(context: Context) {
            val i = Intent(context, AirPlayService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AirPlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
