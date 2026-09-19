package com.cressu.screendock.renderer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.cressu.screendock.bridge.NativeBridge
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Decodes RAOP audio (AAC-LC / AAC-ELD / ALAC) and plays it back through an
 * [AudioTrack]. One decrypted packet at a time is pushed in from UxPlay's
 * native audio thread via
 * `RaopCallbackHandler.onAudioData` -> [feedAudio].
 *
 * ### Codecs (`ct` values, per RAOP)
 * - `4` AAC-LC and `8` AAC-ELD -> platform [MediaCodec].
 * - `2` ALAC -> platform [MediaCodec] configured with the 36-byte ALAC magic
 *   cookie. Many cheap TV boxes have **no** hardware ALAC decoder, in which case
 *   MediaCodec throws [IllegalStateException] at configure/feed time; on such
 *   devices we transparently fall back to the bundled software ALAC decoder
 *   exposed by [NativeBridge] (`alacInit` / `alacDecode` / `alacDestroy`).
 *
 * ### Resilience (NFR-6)
 * Any [IllegalStateException] raised by [MediaCodec] is swallowed here:
 * 1. the failing codec type's hardware path is blacklisted for the rest of the
 *    session (so we never retry-storm a known-bad decoder),
 * 2. for ALAC the software fallback is engaged when [swAlacEnabled],
 * 3. the exception is **never** propagated to the native caller, because doing
 *    so would tear down the entire AirPlay session.
 *
 * ### Threading
 * [feedAudio] and [onFormat] are invoked on a native pthread (not a JVM looper
 * thread). All codec + AudioTrack state is serialized through [codecLock], and
 * [release] is idempotent so it can be called from any teardown path.
 *
 * ### A/V sync
 * PTS-based pacing aligns audio writes to the same NTP timeline the video path
 * uses (both share `ntp_time_remote`). The first packet anchors `ptsBaseNs ↔
 * wallBaseNs`; subsequent packets are paced (sleep if ahead) or dropped (if
 * behind) to keep drift within human-perceptual thresholds (~40–80 ms).
 */
class AudioRenderer {

    /**
     * Kill-switch for the software ALAC fallback. When `false` a device that
     * lacks a hardware ALAC decoder will simply drop audio instead of using the
     * bundled native decoder. Mutable so a caller can disable it at runtime.
     */
    @Volatile
    var swAlacEnabled: Boolean = true

    /** Serializes every codec + track mutation (see "Threading" above). */
    private val codecLock = ReentrantLock()

    // --- output (fixed: 44.1 kHz / stereo / PCM16) -------------------------
    @Volatile
    private var audioTrack: AudioTrack? = null

    // --- active decoder state (all touched under codecLock) -----------------
    private var codec: MediaCodec? = null
    private var swAlacHandle: Long = 0L
    private var configuredCt: Int = CT_NONE
    private var configuredSpf: Int = 0

    /** HW codec types that threw IllegalStateException this session. */
    private val hwBlacklist: MutableSet<Int> = HashSet()

    // --- A/V sync: PTS pacing anchor (matches VideoRenderer's PTS scheduling) ---
    // ptsNs is ntp_time_remote — same clock domain as video. We anchor the first
    // audio packet to System.nanoTime(), then pace subsequent writes so audio
    // doesn't drift ahead of (or fall behind) the video timeline.
    @Volatile private var ptsBaseNs: Long = 0
    @Volatile private var wallBaseNs: Long = 0
    @Volatile private var hasSyncAnchor: Boolean = false
    @Volatile private var consecutiveDrops: Int = 0
    // Debug: count syncAudio calls for timeline diagnosis (temporary)
    private var syncDbgCount: Int = 0

    // --- volume -------------------------------------------------------------
    private var currentVolumeDb: Float = MAX_DB
    private var currentLinearGain: Float = 1.0f

    @Volatile
    private var released: Boolean = false

    // ---- public API --------------------------------------------------------

    /**
     * Called when the audio format is established by the sender. (Re)configures
     * the decoder pipeline for [ct] + [samplesPerFrame] while keeping the
     * PCM [AudioTrack] (its output format never changes). Safe to call
     * repeatedly; identical configs are no-ops.
     *
     * @param ct RAOP codec type: `2` ALAC, `4` AAC-LC, `8` AAC-ELD.
     * @param samplesPerFrame Frame length in samples (e.g. 352 for mirrored
     *        ALAC, 480 for AAC-ELD, 1024 for AAC-LC). Informational for the
     *        platform decoder (the codec-specific config already encodes it).
     */
    fun onFormat(ct: Int, samplesPerFrame: Int) {
        codecLock.withLock {
            if (released) return@withLock
            if (ct == configuredCt && samplesPerFrame == configuredSpf &&
                (codec != null || swAlacHandle != 0L)
            ) {
                return@withLock // already configured for exactly this format
            }
            teardownDecodersLocked()
            ensureTrackLocked()
            setupCodecLocked(ct, samplesPerFrame)
            configuredCt = ct
            configuredSpf = samplesPerFrame
            Log.i(TAG, "onFormat(ct=$ct, spf=$samplesPerFrame)")
        }
    }

    /**
     * Feed one decrypted RAOP audio packet. Invoked on a **native thread**, so
     * the whole path is serialized on [codecLock]. Any [IllegalStateException]
     * from [MediaCodec] is contained here: the HW path is blacklisted, the SW
     * ALAC fallback runs (if applicable), and nothing escapes to native.
     *
     * @param data Raw decrypted payload of a single codec frame/packet.
     * @param ct RAOP codec type for *this* packet.
     * @param ptsNs Presentation timestamp in nanoseconds (forwarded to
     *        MediaCodec as microseconds; not used for explicit A/V sync in v1).
     * @param seqNum RTP-style sequence number (used only for log de-duping).
     */
    fun feedAudio(data: ByteArray, ct: Int, ptsNs: Long, seqNum: Int) {
        codecLock.withLock {
            try {
                feedAudioLocked(data, ct, ptsNs, seqNum)
            } catch (ise: IllegalStateException) {
                // NFR-6: never propagate to the native thread.
                onCodecFailureLocked(ct)
                if (ct == CT_ALAC && swAlacEnabled && swAlacHandle != 0L) {
                    try {
                        feedSwAlacLocked(data)
                    } catch (t: Throwable) {
                        Log.e(TAG, "SW ALAC decode failed after HW failure", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected feedAudio error (ct=$ct, seq=$seqNum)", t)
            }
        }
    }

    /**
     * Set the playback volume in dB. Clamped to [-144, 0]: `<= -144` mutes
     * (linear 0.0), `>= 0` is unity (1.0), otherwise `10^(db/20)`. Applied to
     * the [AudioTrack] and remembered so it survives track rebuilds.
     */
    fun setVolume(db: Float) {
        codecLock.withLock {
            currentVolumeDb = db.coerceIn(MIN_DB, MAX_DB)
            currentLinearGain = dbToLinear(currentVolumeDb)
            try {
                audioTrack?.setVolume(currentLinearGain)
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack.setVolume failed", t)
            }
        }
    }

    /**
     * Drop all state and reset for a new session: tears down the active
     * decoder(s) and [AudioTrack] and clears the HW blacklist so the new
     * session re-probes the platform decoders.
     */
    fun reset() {
        codecLock.withLock {
            teardownDecodersLocked()
            stopAndReleaseTrackLocked()
            hwBlacklist.clear()
            configuredCt = CT_NONE
            configuredSpf = 0
            hasSyncAnchor = false   // re-anchor on next session
            consecutiveDrops = 0
            Log.i(TAG, "reset()")
        }
    }

    /** Idempotent teardown. Release the decoders, the AudioTrack, and the SW ALAC handle. */
    fun release() {
        codecLock.withLock {
            if (released) return@withLock
            released = true
            teardownDecodersLocked()
            stopAndReleaseTrackLocked()
            hwBlacklist.clear()
            configuredCt = CT_NONE
            configuredSpf = 0
            Log.i(TAG, "release()")
        }
    }

    // ---- internals: feed / route ------------------------------------------

    private fun feedAudioLocked(data: ByteArray, ct: Int, ptsNs: Long, seqNum: Int) {
        if (data.isEmpty()) return
        ensureConfiguredLocked(ct)
        ensureTrackLocked()

        // A/V sync: pace or drop this packet to align with the PTS timeline.
        if (!syncAudio(ptsNs)) return   // dropped to maintain sync

        when {
            // ALAC whose HW path is unavailable/blacklisted -> SW decoder.
            ct == CT_ALAC && codec == null && swAlacHandle != 0L ->
                feedSwAlacLocked(data)

            codec != null ->
                feedHwLocked(data, ptsNs)

            else -> {
                // No usable path (unsupported ct, or SW disabled): drop, log lightly.
                if (seqNum % 64 == 0) {
                    Log.w(TAG, "No audio path for ct=$ct (seq=$seqNum); dropping")
                }
            }
        }
    }

    /**
     * PTS-based A/V sync: aligns audio writes to the same NTP timeline the video
     * path uses. Returns `true` to play the packet, `false` to drop it.
     *
     * `drift = now − expected`, where `expected = wallBase + (pts − ptsBase)`:
     * - drift < 0 → packet arrived EARLY (before its playout time). AirPlay
     *   senders intentionally lead audio by ~100–200 ms, so this is the normal
     *   case: sleep down to a small lead, like a jitter buffer.
     * - drift > 0 → packet arrived LATE (playout time already passed): play
     *   immediately; if very late, drop to catch up (capped at [MAX_DROPS]
     *   consecutive, beyond which we re-anchor instead of dropping forever).
     */
    private fun syncAudio(ptsNs: Long): Boolean {
        if (!hasSyncAnchor) {
            ptsBaseNs = ptsNs
            wallBaseNs = System.nanoTime()
            hasSyncAnchor = true
            consecutiveDrops = 0
            return true
        }

        val expectedNs = wallBaseNs + (ptsNs - ptsBaseNs)
        val nowNs = System.nanoTime()
        val driftNs = nowNs - expectedNs   // negative = early, positive = late

        if (syncDbgCount < 60 || syncDbgCount % 256 == 0) {
            Log.d(TAG, "sync dbg #${syncDbgCount} drift=${driftNs / 1_000_000}ms")
        }
        syncDbgCount++

        if (driftNs < -AHEAD_THRESHOLD_NS) {
            // Early — sleep until we're LEAD from the intended playout time.
            val sleepMs = (-driftNs - LEAD_NS) / 1_000_000
            // Guard against sender timeline jumps: never stall the audio
            // thread for more than 100 ms on a single packet.
            val clampedMs = sleepMs.coerceAtMost(100)
            if (clampedMs > 1) {
                try { Thread.sleep(clampedMs) } catch (e: InterruptedException) { /* ignore */ }
            }
            consecutiveDrops = 0
            return true
        }

        if (driftNs > BEHIND_THRESHOLD_NS) {
            // Late — drop to let the stream catch up.
            consecutiveDrops++
            if (consecutiveDrops > MAX_DROPS) {
                // Too far behind — re-anchor instead of endless silence.
                Log.w(TAG, "syncAudio: ${consecutiveDrops} consecutive drops, re-anchoring")
                ptsBaseNs = ptsNs
                wallBaseNs = System.nanoTime()
                consecutiveDrops = 0
                return true
            }
            return false
        }

        consecutiveDrops = 0
        return true
    }

    /** Make sure a decoder for [ct] exists; rebuild (and route to SW on failure). */
    private fun ensureConfiguredLocked(ct: Int) {
        if (ct == configuredCt && (codec != null || swAlacHandle != 0L)) return
        teardownDecodersLocked()
        setupCodecLocked(ct, configuredSpf)
        configuredCt = ct
    }

    /**
     * Instantiate the hardware [MediaCodec] for [ct]. For ALAC, if the HW path
     * is already blacklisted we go straight to the software decoder.
     */
    private fun setupCodecLocked(ct: Int, samplesPerFrame: Int) {
        if (ct == CT_ALAC && hwBlacklist.contains(CT_ALAC)) {
            if (swAlacEnabled) {
                ensureSwAlacLocked()
            } else {
                Log.w(TAG, "ALAC HW blacklisted and swAlacEnabled=false; no audio path")
            }
            return
        }
        try {
            val c = createHwCodecLocked(ct) ?: return
            codec = c
            Log.i(TAG, "HW decoder ready (ct=$ct, spf=$samplesPerFrame)")
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "HW MediaCodec IllegalStateException for ct=$ct", ise)
            hwBlacklist.add(ct)
            if (ct == CT_ALAC && swAlacEnabled) ensureSwAlacLocked()
        } catch (t: Throwable) {
            // e.g. createDecoderByType() IOException / no decoder available.
            Log.e(TAG, "HW MediaCodec unavailable for ct=$ct", t)
            hwBlacklist.add(ct)
            if (ct == CT_ALAC && swAlacEnabled) ensureSwAlacLocked()
        }
    }

    /** Build + configure + start the platform decoder. May throw. */
    private fun createHwCodecLocked(ct: Int): MediaCodec? {
        val mime = when (ct) {
            CT_AAC_ELD, CT_AAC_LC -> MediaFormat.MIMETYPE_AUDIO_AAC // "audio/mp4a-latm"
            CT_ALAC -> MIME_AUDIO_ALAC
            else -> {
                Log.w(TAG, "Unsupported ct=$ct; no HW decoder")
                return null
            }
        }
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS)
            when (ct) {
                CT_AAC_ELD -> {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE_ELD) // 39
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(AAC_ELD_CSD_0))
                }
                CT_AAC_LC -> {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE_LC) // 2
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(AAC_LC_CSD_0))
                }
                CT_ALAC -> {
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(ALAC_MAGIC_COOKIE))
                }
            }
        }
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, null, null, 0)
        c.start()
        return c
    }

    // ---- internals: MediaCodec feed / drain --------------------------------

    private fun feedHwLocked(data: ByteArray, ptsNs: Long) {
        val codec = this.codec ?: return
        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex >= 0) {
            val inBuf = codec.getInputBuffer(inputIndex)
            if (inBuf != null) {
                inBuf.clear()
                val n = minOf(data.size, inBuf.capacity())
                inBuf.put(data, 0, n)
                codec.queueInputBuffer(inputIndex, 0, n, ptsNs / 1000L, 0)
            } else {
                // Return the slot so the codec doesn't stall on the next dequeue.
                try {
                    codec.queueInputBuffer(inputIndex, 0, 0, ptsNs / 1000L, 0)
                } catch (t: Throwable) {
                    Log.w(TAG, "queueInputBuffer(empty) failed", t)
                }
            }
        }
        drainOutputLocked()
    }

    /** Non-blocking drain: writes all currently-ready PCM to the [AudioTrack]. */
    private fun drainOutputLocked() {
        val codec = this.codec ?: return
        val track = this.audioTrack
        val info = MediaCodec.BufferInfo()
        var iterations = 0
        loop@ while (iterations < MAX_DRAIN_ITERATIONS) {
            val outIndex = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0 && track != null) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        writePcmLocked(track, chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    iterations++
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Decoder output format: ${codec.outputFormat}")
                    iterations++
                }
                outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> iterations++
                else -> break@loop // MediaCodec.INFO_TRY_AGAIN_LATER (or unexpected)
            }
        }
    }

    // ---- internals: software ALAC ------------------------------------------

    private fun ensureSwAlacLocked() {
        if (swAlacHandle != 0L) return
        val h = try {
            NativeBridge.alacInit(ALAC_FRAME_LENGTH, CHANNELS, BIT_DEPTH, ALAC_PB, ALAC_MB, ALAC_KB)
        } catch (t: Throwable) {
            Log.e(TAG, "NativeBridge.alacInit threw", t)
            0L
        }
        swAlacHandle = h
        if (h == 0L) {
            Log.e(TAG, "Software ALAC unavailable (alacInit returned 0)")
        } else {
            Log.i(TAG, "Software ALAC decoder initialized (handle=$h)")
        }
    }

    private fun feedSwAlacLocked(data: ByteArray) {
        val h = swAlacHandle
        if (h == 0L) return
        val track = this.audioTrack ?: return
        val pcm = try {
            NativeBridge.alacDecode(h, data)
        } catch (t: Throwable) {
            Log.e(TAG, "NativeBridge.alacDecode threw", t)
            ByteArray(0)
        }
        if (pcm.isNotEmpty()) writePcmLocked(track, pcm)
    }

    private fun writePcmLocked(track: AudioTrack, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        try {
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack.write failed", t)
        }
    }

    // ---- internals: failure handling ---------------------------------------

    /** Contain a MediaCodec failure: blacklist HW, kill the codec, maybe arm SW. */
    private fun onCodecFailureLocked(ct: Int) {
        Log.e(TAG, "MediaCodec failure for ct=$ct; blacklisting HW path for this session")
        hwBlacklist.add(ct)
        codec?.let { c ->
            try { c.stop() } catch (t: Throwable) { /* best-effort */ }
            try { c.release() } catch (t: Throwable) { /* best-effort */ }
        }
        codec = null
        if (ct == CT_ALAC && swAlacEnabled) ensureSwAlacLocked()
    }

    // ---- internals: AudioTrack lifecycle -----------------------------------

    private fun ensureTrackLocked() {
        if (audioTrack != null) return

        var minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize=$minBuf; using fallback buffer of $MAX_BUFFER_BYTES bytes")
            minBuf = MAX_BUFFER_BYTES
        }
        // Spec: minBuffer * 4, clamped to a sane ceiling (and never below the floor).
        var bufSize = minBuf * 4
        if (bufSize > MAX_BUFFER_BYTES) bufSize = MAX_BUFFER_BYTES
        if (bufSize < minBuf) bufSize = minBuf

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_MASK)
            .setEncoding(ENCODING)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack creation failed", t)
            null
        } ?: return

        try { track.setVolume(currentLinearGain) } catch (t: Throwable) { /* best-effort */ }
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            try {
                track.play()
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack.play() failed", t)
            }
        } else {
            Log.e(TAG, "AudioTrack not initialized (state=${track.state})")
        }
        audioTrack = track
    }

    private fun teardownDecodersLocked() {
        codec?.let { c ->
            try { c.stop() } catch (t: Throwable) { /* best-effort */ }
            try { c.release() } catch (t: Throwable) { /* best-effort */ }
        }
        codec = null
        if (swAlacHandle != 0L) {
            try {
                NativeBridge.alacDestroy(swAlacHandle)
            } catch (t: Throwable) {
                Log.w(TAG, "NativeBridge.alacDestroy threw", t)
            }
            swAlacHandle = 0L
        }
    }

    private fun stopAndReleaseTrackLocked() {
        audioTrack?.let { t ->
            try { t.pause() } catch (ign: Throwable) { /* best-effort */ }
            try { t.flush() } catch (ign: Throwable) { /* best-effort */ }
            try { t.release() } catch (ign: Throwable) { /* best-effort */ }
        }
        audioTrack = null
    }

    // ---- helpers ------------------------------------------------------------

    private fun dbToLinear(db: Float): Float {
        val clamped = db.coerceIn(MIN_DB, MAX_DB)
        return when {
            clamped <= MIN_DB -> 0.0f          // mute
            clamped >= MAX_DB -> 1.0f          // unity
            else -> Math.pow(10.0, (clamped / 20.0).toDouble()).toFloat()
        }
    }

    companion object {
        private const val TAG = "AirplayAudio"

        // Output is fixed across every codec.
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BIT_DEPTH = 16
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** AudioTrack buffer ceiling (~0.73 s of stereo PCM16 @ 44.1 kHz). */
        private const val MAX_BUFFER_BYTES = 128 * 1024

        private const val MIN_DB = -144.0f
        private const val MAX_DB = 0.0f

        // A/V sync thresholds (human perception of A/V drift is ~40–80 ms).
        private const val AHEAD_THRESHOLD_NS = 40_000_000L   // 40 ms ahead → sleep to pace
        private const val BEHIND_THRESHOLD_NS = 80_000_000L  // 80 ms behind → drop to catch up
        private const val LEAD_NS = 20_000_000L              // keep 20 ms lead after sleeping
        private const val MAX_DROPS = 3                      // cap consecutive drops before re-anchor (fast recovery)

        // RAOP codec types.
        private const val CT_NONE = 0
        private const val CT_ALAC = 2
        private const val CT_AAC_LC = 4
        private const val CT_AAC_ELD = 8

        // AAC profiles for MediaFormat.KEY_AAC_PROFILE.
        private const val AAC_PROFILE_LC = 2
        private const val AAC_PROFILE_ELD = 39

        // Software ALAC decoder parameters (must mirror [ALAC_MAGIC_COOKIE]).
        private const val ALAC_FRAME_LENGTH = 352
        private const val ALAC_PB = 40
        private const val ALAC_MB = 10
        private const val ALAC_KB = 14

        private const val INPUT_TIMEOUT_US = 10_000L
        private const val DRAIN_TIMEOUT_US = 0L // non-blocking drain
        private const val MAX_DRAIN_ITERATIONS = 32

        // MediaFormat.MIMETYPE_AUDIO_ALAC only exists from API 32; the string
        // value is stable on every API level the platform decoder shipped on,
        // so we use the literal to stay compatible with minSdk 24.
        private const val MIME_AUDIO_ALAC = "audio/alac"

        /** AAC-ELD AudioSpecificConfig (frameLengthFlag=1 -> 480 samples/frame). */
        private val AAC_ELD_CSD_0 = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0)

        /** AAC-LC AudioSpecificConfig (2-channel). */
        private val AAC_LC_CSD_0 = byteArrayOf(0x12, 0x10)

        /**
         * The 36-byte ALAC magic cookie the platform ALAC decoder expects as
         * `csd-0`: frameLength=352, 16-bit, pb=40, mb=10, kb=14, 2 channels,
         * 44100 Hz.
         *
         * ```
         * 00 00 00 24 61 6C 61 63 00 00 00 00 00 00 01 60
         * 00 10 28 0A 0E 02 00 FF 00 00 00 00 00 00 00 00
         * 00 00 AC 44
         * ```
         */
        private val ALAC_MAGIC_COOKIE = byteArrayOf(
            0, 0, 0, 0x24, 0x61, 0x6C, 0x61, 0x63, 0, 0, 0, 0, 0, 0, 1, 0x60,
            0, 0x10, 0x28, 0x0A, 0x0E, 2, 0, 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0xAC.toByte(), 0x44,
        )
    }
}
