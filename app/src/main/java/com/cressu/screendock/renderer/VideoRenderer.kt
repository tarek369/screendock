package com.cressu.screendock.renderer

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AirplayVideo"

private const val FPS_WINDOW_NS = 1_000_000_000L
// Generous: macOS delivers a burst of ~30 frames that piled up while the decoder was
// starting (~350 ms). A 16-frame cap overflowed on that burst, discarded the only
// keyframe, and left the screen black (macOS rarely re-sends an IDR for a static screen).
private const val MAX_QUEUED_FRAMES = 240
private const val MAX_QUEUED_BYTES = 24L * 1024 * 1024
private const val RESTART_BACKOFF_NS = 500_000_000L

/**
 * Decodes decrypted Annex-B NAL bundles (H.264 or H.265) with an **asynchronous**
 * [MediaCodec] into the [VideoPipeline]'s input surface.
 *
 * **Latency model**: input is queued on the codec's `onInputBufferAvailable`
 * callback and output is released the instant `onOutputBufferAvailable` fires,
 * with `releaseOutputBuffer(idx, true)` (render immediately). Mirroring is
 * interactive, so we deliberately do NOT pace frames against their PTS: macOS only
 * sends frames when the screen changes, and PTS pacing (or draining output only
 * when the next input arrives) would hold the last update back.
 *
 * **Overflow**: incoming frames go through a [BoundedFrameQueue]; if the decoder
 * falls behind, the backlog is dropped and decoding resumes at the next keyframe
 * rather than feeding a gapped P-frame chain.
 *
 * **Recovery**: the last keyframe bundle is refreshed on every IDR. A codec error
 * (or a parameter-set change, e.g. rotation) restarts the decoder from it.
 *
 * **Threading**: [feedFrame] runs on a native pthread; codec callbacks run on the
 * "VideoDecoder" [HandlerThread]. All codec state is guarded by [codecLock]. No
 * exception is ever propagated to native (NFR-6).
 */
class VideoRenderer {

    private val pipeline = VideoPipeline()

    private val codecLock = Any()
    private val callbackThread = HandlerThread("VideoDecoder").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    // ---- guarded by codecLock ----------------------------------------------
    private var codec: MediaCodec? = null
    private var codecMime: String? = null
    private var codecValid = false
    private var released = false
    private var configuredParams: ByteArray? = null
    private val freeInputs = ArrayDeque<Int>()
    private val frameQueue = BoundedFrameQueue(MAX_QUEUED_FRAMES, MAX_QUEUED_BYTES)
    private var lastKeyframe: ByteArray? = null
    private var lastKeyframeMime: String? = null
    private var lastStartFailNs = 0L
    private var lastFpsTimeNs = 0L
    private var framesSinceLastFps = 0
    private var frameDbgCount = 0L
    private var pumpScheduled = false
    // Audio measurably lands ~40-60 ms after its slot (AudioTrack + output path), so
    // video targets the same slot shifted by that much to line up with what is heard.
    private val playoutClock = PlayoutClock(PlayoutClock.DEFAULT_LATENCY_NS + AUDIO_PATH_LAG_NS)
    private var syncDbgCount = 0

    /**
     * True while the sender is streaming audio. Only then is video held back to the
     * shared playout clock (lip sync); a silent desktop mirror stays at minimum latency.
     */
    @Volatile var audioActive: () -> Boolean = { false }

    @Volatile var frameCount: Long = 0L
        private set
    @Volatile var fps: Float = 0f
        private set

    private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)

    /** Visible (crop-corrected) size of the decoded picture; [VideoSize.UNKNOWN] until known. */
    val videoSize: StateFlow<VideoSize> = _videoSize.asStateFlow()

    /** How the picture maps to the display. Settable at runtime from any thread. */
    var scaleMode: ScaleMode
        get() = pipeline.scaleMode
        set(value) { pipeline.scaleMode = value }

    init {
        // Bring the EGL pipeline up eagerly so the codec's input surface exists
        // before the first frame lands on the native thread.
        pipeline.start()
    }

    /** Set the display surface (UI thread). `null` blanks output without touching the codec. */
    fun setSurface(surface: Surface?) = pipeline.setDisplaySurface(surface)

    /** Source aspect (width/height) of the visible picture; 0 until the codec reports it. */
    fun videoAspect(): Float = pipeline.videoAspect

    /**
     * Feed one decrypted Annex-B NAL bundle (possibly SPS/PPS/IDR together).
     * Called on a NATIVE thread. Never blocks on the decoder.
     */
    fun feedFrame(data: ByteArray, ptsNs: Long, isH265: Boolean) {
        if (data.isEmpty()) return
        synchronized(codecLock) {
            if (released) return
            try {
                feedFrameLocked(data, ptsNs / 1000L, isH265)
            } catch (t: Throwable) {
                Log.e(TAG, "feedFrame error; will restart on next keyframe", t)
                invalidateCodecLocked()
            }
        }
    }

    /** Reset codec on session boundaries (drops the decoder and cached keyframe). */
    fun reset() {
        synchronized(codecLock) {
            invalidateCodecLocked()
            frameQueue.clear()
            lastKeyframe = null
            lastKeyframeMime = null
            lastFpsTimeNs = 0L
            framesSinceLastFps = 0
        }
    }

    /** Release the codec, callback thread and EGL pipeline. Idempotent. */
    fun release() {
        synchronized(codecLock) {
            if (released) return
            released = true
            invalidateCodecLocked()
            frameQueue.clear()
        }
        callbackThread.quitSafely()
        pipeline.release()
    }

    // ---- feed path -----------------------------------------------------------

    private fun feedFrameLocked(data: ByteArray, ptsUs: Long, isH265: Boolean) {
        val mime = if (isH265) MIME_HEVC else MIME_AVC
        val key = AnnexB.isKeyframe(data, isH265)

        if (frameDbgCount < 30 || frameDbgCount % 256 == 0L) {
            Log.d(TAG, "frame dbg #$frameDbgCount len=${data.size} h265=$isH265 key=$key " +
                "codec=${codec != null}/$codecValid q=${frameQueue.size()}")
        }
        frameDbgCount++

        // Refresh the recovery seed on EVERY keyframe (rotation changes the SPS).
        if (key) {
            lastKeyframe = data
            lastKeyframeMime = mime
        }

        val paramsChanged = key && codecValid && codecMime == mime &&
            AnnexB.paramSets(data, isH265)?.let { !it.contentEquals(configuredParams) } == true
        val needsStart = codec == null || !codecValid || codecMime != mime || paramsChanged
        if (needsStart) {
            val seed = if (key) data else lastKeyframe?.takeIf { lastKeyframeMime == mime }
            if (seed == null) return // never saw a keyframe; wait for one
            val now = System.nanoTime()
            if (!key && now - lastStartFailNs < RESTART_BACKOFF_NS) return
            invalidateCodecLocked()
            frameQueue.clear()
            if (!startCodecLocked(mime, seed, isH265)) {
                lastStartFailNs = now
                return
            }
            if (!key) {
                // Re-prime the decoder with the stale keyframe so it has a reference.
                Log.w(TAG, "codec restarted from stale keyframe; picture may glitch until next IDR")
                frameQueue.offer(VideoFrame(seed, ptsUs, true))
            } else if (paramsChanged) {
                Log.i(TAG, "parameter sets changed; decoder restarted")
            }
        }

        if (frameQueue.offer(VideoFrame(data, ptsUs, key)) == OfferResult.OVERFLOWED) {
            // Don't wait for a fresh IDR (macOS may never send one): restart from the
            // last keyframe on the next frame. A brief glitch beats a black screen.
            Log.w(TAG, "decoder backlog overflow; restarting from last keyframe")
            invalidateCodecLocked()
            frameQueue.clear()
            return
        }
        pumpInputLocked()
    }

    /** Move queued frames into free codec input buffers. Caller holds [codecLock]. */
    private fun pumpInputLocked() {
        val c = codec ?: return
        while (freeInputs.isNotEmpty()) {
            val head = frameQueue.peek() ?: return
            // Lip sync: feed the decoder just before the frame's slot on the shared
            // playout clock (holding compressed input never starves output buffers).
            val delayNs = playoutClock.delayNs(head.ptsUs * 1000L)
            val holdMs = AvSyncPolicy.videoHoldMs(delayNs?.minus(DECODE_LEAD_NS), audioActive())
            if (syncDbgCount < 40 || syncDbgCount % 600 == 0) {
                Log.d(TAG, "av sync: video delay=${delayNs?.div(1_000_000L)}ms hold=${holdMs}ms audio=${audioActive()}")
            }
            syncDbgCount++
            if (holdMs > 0L) {
                schedulePumpLocked(holdMs)
                return
            }
            val frame = frameQueue.poll() ?: return
            val idx = freeInputs.removeFirst()
            val buf = c.getInputBuffer(idx)
            if (buf == null || buf.capacity() < frame.data.size) {
                // Return the slot empty and avoid feeding a gapped chain.
                Log.w(TAG, "input buffer too small (${buf?.capacity()} < ${frame.data.size}); dropping to next IDR")
                c.queueInputBuffer(idx, 0, 0, frame.ptsUs, 0)
                frameQueue.markCorrupt()
                continue
            }
            buf.clear()
            buf.put(frame.data)
            c.queueInputBuffer(idx, 0, frame.data.size, frame.ptsUs, 0)
        }
    }

    private fun schedulePumpLocked(delayMs: Long) {
        if (pumpScheduled) return
        pumpScheduled = true
        callbackHandler.postDelayed({
            synchronized(codecLock) {
                pumpScheduled = false
                if (!released) runCatching { pumpInputLocked() }.onFailure { Log.e(TAG, "scheduled pump failed", it) }
            }
        }, delayMs)
    }

    // ---- codec lifecycle -----------------------------------------------------

    private fun startCodecLocked(mime: String, keyData: ByteArray, isH265: Boolean): Boolean {
        val surface = pipeline.inputSurface
        if (surface == null) {
            Log.w(TAG, "startCodec: pipeline input surface not ready")
            return false
        }
        val dec = try {
            createConfigured(mime, keyData, isH265, surface, lowLatency = true)
        } catch (e: Exception) {
            Log.w(TAG, "low-latency decoder start failed ($e); retrying without")
            try {
                createConfigured(mime, keyData, isH265, surface, lowLatency = false)
            } catch (t: Exception) {
                Log.e(TAG, "Failed to create $mime decoder", t)
                return false
            }
        }
        codec = dec
        codecMime = mime
        codecValid = true
        configuredParams = AnnexB.paramSets(keyData, isH265)
        freeInputs.clear()
        Log.i(TAG, "Created $mime decoder (async, render-immediately)")
        return true
    }

    /** Create, configure and start a decoder; releases it on ANY failure before rethrowing. */
    private fun createConfigured(
        mime: String, keyData: ByteArray, isH265: Boolean, surface: Surface, lowLatency: Boolean,
    ): MediaCodec {
        val dec = MediaCodec.createDecoderByType(mime)
        try {
            dec.setCallback(DecoderCallback(), callbackHandler)
            dec.configure(buildFormat(mime, lowLatency, keyData, isH265), surface, null, 0)
            dec.start()
            return dec
        } catch (t: Throwable) {
            runCatching { dec.release() }
            throw t
        }
    }

    private fun buildFormat(mime: String, lowLatency: Boolean, keyData: ByteArray, isH265: Boolean): MediaFormat =
        MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            // Hints only: surface-mode configure needs nonzero dims; real size comes from csd.
            setInteger(MediaFormat.KEY_WIDTH, 1920)
            setInteger(MediaFormat.KEY_HEIGHT, 1080)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, 240)
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1)
            if (lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            if (isH265) {
                AnnexB.extractNals(keyData, AnnexB.HEVC_PARAM_TYPES, true)
                    ?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            } else {
                AnnexB.extractNals(keyData, AnnexB.AVC_SPS, false)
                    ?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                AnnexB.extractNals(keyData, AnnexB.AVC_PPS, false)
                    ?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }
        }

    /** Stop + release the current codec. Safe to call repeatedly. Caller holds [codecLock]. */
    private fun invalidateCodecLocked() {
        codecValid = false
        val c = codec
        codec = null
        codecMime = null
        configuredParams = null
        freeInputs.clear()
        if (c != null) {
            runCatching { c.stop() }.onFailure { Log.w(TAG, "codec.stop failed", it) }
            runCatching { c.release() }.onFailure { Log.w(TAG, "codec.release failed", it) }
        }
    }

    /** Restart from the cached keyframe after a codec error (runs on the callback thread). */
    private fun recoverFromErrorLocked() {
        invalidateCodecLocked()
        frameQueue.clear()
        val seed = lastKeyframe ?: return
        val mime = lastKeyframeMime ?: return
        if (startCodecLocked(mime, seed, mime == MIME_HEVC)) {
            frameQueue.offer(VideoFrame(seed, 0L, true))
            Log.w(TAG, "codec recovered from last keyframe")
        } else {
            lastStartFailNs = System.nanoTime()
        }
    }

    // ---- callbacks (callbackThread) -------------------------------------------

    private inner class DecoderCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) = guarded(c) {
            freeInputs.addLast(index)
            pumpInputLocked()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = guarded(c) {
            val render = info.size > 0
            c.releaseOutputBuffer(index, render) // render immediately: lowest mirroring latency
            if (render) bumpStatsLocked()
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = guarded(c) {
            val size = VideoGeometry.visibleSize(
                format.intOrNull(MediaFormat.KEY_WIDTH) ?: 0,
                format.intOrNull(MediaFormat.KEY_HEIGHT) ?: 0,
                format.intOrNull("crop-left") ?: -1,
                format.intOrNull("crop-right") ?: -1,
                format.intOrNull("crop-top") ?: -1,
                format.intOrNull("crop-bottom") ?: -1,
            )
            Log.i(TAG, "decoder output format: $format -> visible ${size.width}x${size.height}")
            if (size.width > 0) {
                pipeline.videoAspect = size.aspect
                _videoSize.value = size
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "MediaCodec error (recoverable=${e.isRecoverable} transient=${e.isTransient})", e)
            synchronized(codecLock) {
                if (released || c !== codec) return
                // Defer to a fresh message: don't tear the codec down inside its own callback.
                callbackHandler.post {
                    synchronized(codecLock) {
                        if (!released && c === codec) {
                            runCatching { recoverFromErrorLocked() }
                                .onFailure { Log.e(TAG, "codec recovery failed", it) }
                        }
                    }
                }
            }
        }

        private inline fun guarded(c: MediaCodec, block: () -> Unit) {
            synchronized(codecLock) {
                if (released || c !== codec) return
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(TAG, "decoder callback failed; invalidating codec", t)
                    invalidateCodecLocked()
                }
            }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun bumpStatsLocked() {
        frameCount++
        framesSinceLastFps++
        val now = System.nanoTime()
        if (lastFpsTimeNs == 0L) {
            lastFpsTimeNs = now
            framesSinceLastFps = 0
            return
        }
        val elapsedNs = now - lastFpsTimeNs
        if (elapsedNs >= FPS_WINDOW_NS) {
            fps = framesSinceLastFps * 1_000_000_000f / elapsedNs.toFloat()
            lastFpsTimeNs = now
            framesSinceLastFps = 0
        }
    }

    private companion object {
        const val MIME_AVC = "video/avc"
        const val MIME_HEVC = "video/hevc"
        /** Typical hardware decode time: queue input this early so output lands on time. */
        const val DECODE_LEAD_NS = 30_000_000L
        /** Measured on OnePlus Pad 3 (2026-10-23): audio delay−queued ≈ −45 ms. */
        const val AUDIO_PATH_LAG_NS = 50_000_000L
    }
}
