package com.cressu.screendock.renderer

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

private const val TAG = "AirplayVideo"

private const val INPUT_TIMEOUT_US = 10_000L
private const val OUTPUT_TIMEOUT_US = 0L
private const val FPS_WINDOW_NS = 1_000_000_000L

/**
 * Decodes decrypted Annex-B NAL bundles (H.264 or H.265) with [MediaCodec]
 * into the [VideoPipeline]'s input surface and schedules each rendered buffer
 * against a PTS → wall-clock anchor for A/V sync (DESIGN §7.1.4).
 *
 * **Codec lifecycle** is lazy and resilient: the decoder is created on the first
 * keyframe seen and (re)created only on subsequent keyframes after a failure or
 * a codec-type change. Intermediate non-keyframe frames are dropped until a
 * keyframe arrives, because a hardware decoder cannot sync mid-GOP.
 *
 * **Threading**: [feedFrame] is invoked on a UxPlay native pthread; all codec
 * state is guarded by [codecLock]. **Per NFR-6**, no exception is ever
 * propagated to the native caller — a codec [IllegalStateException] (or any
 * other [Throwable]) invalidates the codec and we wait for the next keyframe to
 * restart, never tearing down the process.
 */
class VideoRenderer {

    private val pipeline = VideoPipeline()

    private val codecLock = Any()

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var codecMime: String? = null
    @Volatile private var codecValid: Boolean = false

    // Reused BufferInfo — feedFrame is single-threaded (native rtp_video) and
    // always under codecLock, so reuse is safe and avoids per-frame allocation.
    private val bufferInfo = MediaCodec.BufferInfo()

    // ---- A/V sync anchor (set once, on the first OUTPUT frame) --------------
    @Volatile private var anchorSet: Boolean = false
    @Volatile private var wallBaseNs: Long = 0L
    @Volatile private var ptsBaseUs: Long = 0L

    /** Most recent keyframe bundle (SPS/PPS + IDR); seed for codec restarts. */
    private var lastKeyframe: ByteArray? = null

    // ---- lightweight stats (read off the decode thread) --------------------
    @Volatile var frameCount: Long = 0L
        private set
    @Volatile var fps: Float = 0f
        private set
    private var lastFpsTimeNs: Long = 0L
    private var framesSinceLastFps: Int = 0

    // Debug: frame-arrival tracing (temporary).
    private var frameDbgCount: Long = 0L

    init {
        // Bring the EGL pipeline up eagerly so the codec's input surface is
        // ready before the first frame lands on the native thread.
        pipeline.start()
    }

    /**
     * Set the display surface (forwarded to the pipeline). Call from the UI
     * thread. Passing `null` blanks the output without touching the codec.
     */
    fun setSurface(surface: Surface?) {
        pipeline.setDisplaySurface(surface)
    }

    /** Source aspect (width/height) of the decoded stream; 0 until the codec reports it. */
    fun videoAspect(): Float = pipeline.videoAspect

    /**
     * Feed one decrypted NAL bundle. Called on a NATIVE thread (be thread-safe).
     *
     * The bundle may carry several NALUs (SPS/PPS/IDR…), each already prefixed
     * with the Annex-B start code `00 00 00 01`. The codec is (re)started only
     * when a keyframe is detected; otherwise the frame is fed to the running
     * decoder or dropped if the decoder is not yet up.
     *
     * @param data Annex-B NAL units, decrypted.
     * @param ptsNs Presentation timestamp in nanoseconds (NTP-derived).
     * @param isH265 `true` for H.265/HEVC, `false` for H.264/AVC.
     */
    fun feedFrame(data: ByteArray, ptsNs: Long, isH265: Boolean) {
        if (data.isEmpty()) return
        synchronized(codecLock) {
            try {
                feedFrameLocked(data, ptsNs, isH265)
            } catch (ise: IllegalStateException) {
                // MediaCodec surface/queue corruption — most common codec failure.
                Log.e(TAG, "MediaCodec IllegalStateException, will restart on next keyframe", ise)
                invalidateCodecLocked()
            } catch (t: Throwable) {
                // Never propagate to native (NFR-6).
                Log.e(TAG, "feedFrame unexpected error", t)
                invalidateCodecLocked()
            }
        }
    }

    /** Reset codec on session boundaries (drops the decoder and the sync anchor). */
    fun reset() {
        synchronized(codecLock) {
            invalidateCodecLocked()
            anchorSet = false
            wallBaseNs = 0L
            ptsBaseUs = 0L
            lastFpsTimeNs = 0L
            framesSinceLastFps = 0
            lastKeyframe = null
        }
    }

    /** Release the codec and the underlying EGL pipeline. */
    fun release() {
        synchronized(codecLock) {
            invalidateCodecLocked()
        }
        pipeline.release()
    }

    // ---- decode thread (native rtp_video) implementation --------------------

    private fun feedFrameLocked(data: ByteArray, ptsNs: Long, isH265: Boolean) {
        val mime = if (isH265) MIME_HEVC else MIME_AVC

        if (frameDbgCount < 30 || frameDbgCount % 256 == 0L) {
            Log.d(TAG, "frame dbg #${frameDbgCount} len=${data.size} h265=$isH265 " +
                "key=${isKeyframe(data, isH265)} codec=${codec != null}/$codecValid")
        }
        frameDbgCount++

        // (Re)start the codec lazily and only on a keyframe.
        val needsStart = codec == null || !codecValid || codecMime != mime
        if (needsStart) {
            val key = isKeyframe(data, isH265)
            if (key) lastKeyframe = data
            // Senders only emit SPS/PPS/IDR at stream start; if the codec died
            // mid-GOP, restart from the remembered keyframe (decoder conceals
            // the gap and recovers at the next in-band IDR) instead of staying
            // dark until a new keyframe that may never arrive.
            val startFrom = data.takeIf { key } ?: lastKeyframe
            if (startFrom == null) {
                // Never saw a keyframe; drop until one arrives.
                return
            }
            invalidateCodecLocked()
            if (!ensureCodecLocked(mime, startFrom, isH265)) {
                // Pipeline not ready or no decoder — drop and try again next keyframe.
                return
            }
            if (!key) {
                Log.w(TAG, "codec restarted from stale keyframe; picture may glitch until next IDR")
            }
        }

        val c = codec ?: return
        val ptsUs = ptsNs / 1000L

        // Queue one input buffer carrying the whole NAL bundle.
        val inIdx = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIdx >= 0) {
            val buf = c.getInputBuffer(inIdx)
            if (buf != null && buf.capacity() >= data.size) {
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
            } else {
                // No room (rare) — return the empty buffer so it isn't leaked.
                c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                Log.w(TAG, "input buffer too small (${buf?.capacity()} < ${data.size}), dropping NALs")
            }
        }

        drainOutputLocked()
    }

    /**
     * Create + start a fresh [MediaCodec] decoder for [mime], configured against
     * [VideoPipeline.inputSurface]. The codec-specific data (SPS/PPS for H.264,
     * VPS/SPS/PPS for H.265) is extracted from the keyframe bundle [keyData] so
     * the decoder learns the sender's REAL resolution — the numeric
     * KEY_WIDTH/KEY_HEIGHT are only configure-time requirements on some builds
     * and get overridden by the in-band parameter sets.
     *
     * @return `true` if the codec is started and ready.
     */
    private fun ensureCodecLocked(mime: String, keyData: ByteArray, isH265: Boolean): Boolean {
        val surface = pipeline.inputSurface
        if (surface == null) {
            Log.w(TAG, "ensureCodec: pipeline input surface not ready")
            return false
        }
        return try {
            val dec = MediaCodec.createDecoderByType(mime)
            try {
                dec.configure(buildFormat(mime, lowLatency = true, keyData, isH265), surface, null, 0)
            } catch (e: IllegalArgumentException) {
                // Some decoders reject the low-latency hint — fall back cleanly.
                Log.w(TAG, "configure with low-latency failed; retrying without")
                dec.configure(buildFormat(mime, lowLatency = false, keyData, isH265), surface, null, 0)
            }
            dec.start()
            codec = dec
            codecMime = mime
            codecValid = true
            Log.i(TAG, "Created $mime decoder")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create $mime decoder", t)
            false
        }
    }

    private fun buildFormat(mime: String, lowLatency: Boolean, keyData: ByteArray, isH265: Boolean): MediaFormat {
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            // Surface-mode configure requires nonzero dimensions on most
            // builds (bare IllegalArgumentException otherwise). These are
            // only hints — the csd below and in-band SPS carry the real size.
            setInteger(MediaFormat.KEY_WIDTH, 1920)
            setInteger(MediaFormat.KEY_HEIGHT, 1080)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, 240)
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1)
            if (lowLatency) {
                // Real-time operating mode: minimizes decoder output hold-back
                // (available on API 30+; harmless string on older levels).
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            // Real parameter sets from the sender's keyframe: makes the decoder
            // size itself to the actual stream (which may exceed the hints).
            if (isH265) {
                extractNals(keyData, HEVC_PARAM_TYPES, isH265 = true)?.let { csd ->
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
                }
            } else {
                extractNals(keyData, intArrayOf(7), isH265 = false)?.let { csd ->
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
                }
                extractNals(keyData, intArrayOf(8), isH265 = false)?.let { csd ->
                    setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(csd))
                }
            }
        }
        return format
    }

    /**
     * Concatenate the Annex-B NAL units of the requested types (with their
     * start codes) from a keyframe bundle, or `null` if none found.
     * H.264 NAL type = `b & 0x1F`; H.265 NAL type = `(b >> 1) & 0x3F`.
     * Handles both 4-byte (`00 00 00 01`) and 3-byte (`00 00 01`) start codes.
     */
    private fun extractNals(data: ByteArray, types: IntArray, isH265: Boolean): ByteArray? {
        val starts = startCodePositions(data)
        val out = java.io.ByteArrayOutputStream()
        var found = false
        for (idx in starts.indices) {
            val sc = starts[idx]
            val scLen = if (data.size - sc >= 4 && data[sc + 3] == 1.toByte()) 4 else 3
            val nalStart = sc + scLen
            val nalEnd = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            if (nalStart >= nalEnd) continue
            val b = data[nalStart].toInt() and 0xFF
            val type = if (isH265) (b shr 1) and 0x3F else b and 0x1F
            if (types.contains(type)) {
                found = true
                out.write(data, sc, nalEnd - sc)
            }
        }
        return if (found) out.toByteArray() else null
    }

    /** Offsets of every Annex-B start code (4-byte preferred, then 3-byte), in order. */
    private fun startCodePositions(data: ByteArray): List<Int> {
        val starts = ArrayList<Int>()
        val n = data.size
        var i = 0
        while (i + 3 < n) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                starts.add(i); i += 4
            } else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                starts.add(i); i += 3
            } else {
                i++
            }
        }
        return starts
    }

    /**
     * Drain all currently-available output buffers, rendering each at its
     * PTS-anchored wall-clock time. Non-blocking (zero timeout) so it returns
     * to the native caller as soon as the queue is empty.
     */
    private fun drainOutputLocked() {
        val c = codec ?: return
        while (true) {
            val idx = c.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                idx >= 0 -> {
                    val ptsUs = bufferInfo.presentationTimeUs
                    if (!anchorSet) {
                        // Anchor wall-clock to this first rendered frame's PTS.
                        wallBaseNs = System.nanoTime()
                        ptsBaseUs = ptsUs
                        anchorSet = true
                    }
                    val renderTs = wallBaseNs + (ptsUs - ptsBaseUs) * 1000L
                    runCatching { c.releaseOutputBuffer(idx, renderTs) }
                        .onFailure { Log.e(TAG, "releaseOutputBuffer failed", it) }
                    bumpStatsLocked()
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    val w = fmt.getInteger(MediaFormat.KEY_WIDTH, 0)
                    val h = fmt.getInteger(MediaFormat.KEY_HEIGHT, 0)
                    Log.i(TAG, "decoder output format: ${w}x${h}")
                    if (w > 0 && h > 0) {
                        pipeline.videoAspect = w.toFloat() / h.toFloat()
                    }
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> { /* keep draining other transient info codes */ }
            }
        }
    }

    /** Stop + release the current codec and mark it invalid. Safe to call twice. */
    private fun invalidateCodecLocked() {
        codecValid = false
        val c = codec
        codec = null
        codecMime = null
        if (c != null) {
            runCatching { c.stop() }.onFailure { Log.w(TAG, "codec.stop failed", it) }
            runCatching { c.release() }.onFailure { Log.w(TAG, "codec.release failed", it) }
        }
    }

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

    /**
     * Scan `data` for Annex-B NAL units (3- or 4-byte start codes) and report
     * whether any is a keyframe indicator.
     *
     * - H.264 NAL type = `b & 0x1F`, keyframe ∈ {5 (IDR), 7 (SPS)}.
     * - H.265 NAL type = `(b >> 1) & 0x3F`, keyframe ∈ {19, 20, 21 (IDR/BLA),
     *   32 (VPS), 33 (SPS)}.
     */
    private fun isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        val keyTypes = if (isH265) HEVC_KEY_TYPES else AVC_KEY_TYPES
        for (sc in startCodePositions(data)) {
            val scLen = if (data.size - sc >= 4 && data[sc + 3] == 1.toByte()) 4 else 3
            val nalStart = sc + scLen
            if (nalStart >= data.size) continue
            val b = data[nalStart].toInt() and 0xFF
            val type = if (isH265) (b shr 1) and 0x3F else b and 0x1F
            if (keyTypes.contains(type)) return true
        }
        return false
    }

    private companion object {
        const val MIME_AVC = "video/avc"
        const val MIME_HEVC = "video/hevc"
        val AVC_KEY_TYPES = intArrayOf(5, 7)
        val HEVC_KEY_TYPES = intArrayOf(19, 20, 21, 32, 33)
        val HEVC_PARAM_TYPES = intArrayOf(32, 33, 34)   // VPS, SPS, PPS -> csd-0
    }
}
