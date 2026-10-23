package com.cressu.screendock.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cressu.screendock.bridge.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the ExoPlayer used for AirPlay video (YouTube etc.). All player access happens on the
 * main looper; the public methods may be called from any thread (native httpd thread) and only
 * post work, so [play] returns immediately as the native contract requires.
 *
 * Playback info is PUSHED to native every [TICK_MS] ms and on each state change via
 * [NativeBridge.updateHlsPlaybackInfo]; duration -1 signals ended/error to the sender.
 */
class HlsPlayerController(
    context: Context,
    private val handleProvider: () -> Long,
    /** Invoked on the main thread when playback ended or failed on its own. */
    private val onFinished: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _player = MutableStateFlow<Player?>(null)
    /** Non-null while a video is loaded; the UI renders it with a PlayerView. */
    val player: StateFlow<Player?> = _player

    // Main-thread only.
    private var exo: ExoPlayer? = null
    private var generation = 0
    /** Fraction (0..1) still waiting for the duration to become known; main-thread only. */
    private var pendingFraction: StartPosition.Fraction? = null

    private val tick = object : Runnable {
        override fun run() {
            val p = exo ?: return
            push(p)
            main.postDelayed(this, TICK_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val p = exo ?: return
            applyPendingFraction(p)
            push(p)
            if (state == Player.STATE_ENDED) finish(p)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { exo?.let(::push) }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            exo?.let { applyPendingFraction(it); push(it) }
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "HLS playback error: ${error.errorCodeName}", error)
            exo?.let(::finish)
        }
    }

    /**
     * @param startPosition native encoding: >= 0 seconds, < 0 fraction of the duration
     *        (see [StartPosition.decode]); AirPlay V2 /play sends the latter for "Start-Position".
     */
    fun play(location: String, startPosition: Float) {
        main.post {
            releaseOnMain()
            if (!VideoSource.isAllowed(location)) {
                Log.w(TAG, "refusing video URL (not https and not a local host)")
                NativeBridge.updateHlsPlaybackInfo(handleProvider(), -1.0, 0.0, 0f, false)
                return@post
            }
            val gen = ++generation
            val p = ExoPlayer.Builder(appContext)
                .setHandleAudioBecomingNoisy(true)
                .build()
            p.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            p.addListener(listener)
            exo = p
            val start = StartPosition.decode(startPosition)
            pendingFraction = (start as? StartPosition.Fraction)?.takeIf { it.fraction > 0.0 }
            val startMs = start.resolveMs(null) ?: C.TIME_UNSET
            p.setMediaItem(buildMediaItem(location), startMs)
            p.playWhenReady = true
            p.prepare()
            _player.value = p
            main.post(tick)
            Log.i(TAG, "video play gen=$gen kind=${VideoSource.classify(location)} start=$start")
        }
    }

    private fun buildMediaItem(location: String): MediaItem {
        val b = MediaItem.Builder().setUri(location)
        // Extension-less HLS URLs would otherwise be sniffed as progressive; everything else is
        // left to DefaultMediaSourceFactory (content type / extension / sniffing).
        if (VideoSource.classify(location) == VideoKind.HLS) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    private fun applyPendingFraction(p: ExoPlayer) {
        val f = pendingFraction ?: return
        if (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_ENDED) return
        pendingFraction = null
        if (p.isCurrentMediaItemLive) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        f.resolveMs(dur)?.let { p.seekTo(it) }
    }

    fun stop() { main.post { releaseOnMain() } }

    /** Release on the main thread and wait (bounded) so no push can follow. Never call from main. */
    fun stopAndWait(timeoutMs: Long = 2000L) {
        if (Looper.myLooper() == Looper.getMainLooper()) { releaseOnMain(); return }
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { try { releaseOnMain() } finally { latch.countDown() } }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun scrub(positionSec: Float) {
        main.post { exo?.seekTo((positionSec.coerceAtLeast(0f) * 1000f).toLong()) }
    }

    /** rate 1.0 = play, 0.0 = pause (sender may send other positive rates). */
    fun setRate(rate: Float) {
        main.post {
            val p = exo ?: return@post
            if (rate > 0f) {
                p.setPlaybackSpeed(rate.coerceIn(0.25f, 4f))
                p.playWhenReady = true
            } else {
                p.playWhenReady = false
            }
            push(p)
        }
    }

    /** Release everything; safe from any thread, does not block. */
    fun release() { main.post { releaseOnMain() } }

    // ---- main thread ----

    private fun finish(p: ExoPlayer) {
        val h = handleProvider()
        NativeBridge.updateHlsPlaybackInfo(h, -1.0, 0.0, 0f, false)
        if (exo === p) {
            releaseOnMain()
            onFinished()
        }
    }

    private fun releaseOnMain() {
        main.removeCallbacks(tick)
        pendingFraction = null
        val p = exo ?: return
        exo = null
        _player.value = null
        try {
            p.removeListener(listener)
            p.stop()
            p.release()
        } catch (t: Throwable) {
            Log.w(TAG, "player release failed", t)
        }
    }

    private fun push(p: ExoPlayer) {
        val h = handleProvider()
        val state = p.playbackState
        val live = p.isCurrentMediaItemLive
        val durMs = p.duration
        val durationSec = if (live || durMs == C.TIME_UNSET || durMs < 0) 0.0 else durMs / 1000.0
        val positionSec = (p.currentPosition.coerceAtLeast(0L)) / 1000.0
        val ready = if (live) state == Player.STATE_READY else (state == Player.STATE_READY && durationSec > 0.0)
        val rate = if (p.playWhenReady && state == Player.STATE_READY) p.playbackParameters.speed else 0f
        NativeBridge.updateHlsPlaybackInfo(h, durationSec, positionSec, rate, ready)
    }

    private companion object {
        const val TAG = "AirPlayHls"
        const val TICK_MS = 250L
    }
}
