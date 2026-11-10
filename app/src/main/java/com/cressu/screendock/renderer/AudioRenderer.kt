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
 * 1. the failing decoder is remembered and the next capable platform decoder is
 *    tried ([AudioDecoderSelector]); only when every candidate failed is the codec
 *    type blacklisted for the session (so we never retry-storm a known-bad decoder),
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
class AudioRenderer(
    /** Optional Oboe low-latency sink (default OFF -> AudioTrack path, unchanged). */
    private val nativeConfig: NativeAudioConfig = NativeAudioConfig(),
    /** Device native output (sampleRate, framesPerBurst) hint for Oboe's OpenSL ES backend; 0 = unknown. */
    private val deviceOutput: Pair<Int, Int> = 0 to 0,
) {

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

    // --- optional Oboe sink (all touched under codecLock) --------------------
    /** Native engine handle; 0 = not open. When non-zero it replaces [audioTrack]. */
    private var oboeHandle: Long = 0L
    /** Set after a failed open so we never retry-storm; AudioTrack carries the session. */
    private var oboeUnavailable: Boolean = false
    private var oboeWrites: Long = 0L
    /** PTS of the packet currently being decoded (for decoders that do not report one). */
    private var currentPtsNs: Long = 0L

    // --- active decoder state (all touched under codecLock) -----------------
    private var codec: MediaCodec? = null
    private var swAlacHandle: Long = 0L
    private var configuredCt: Int = CT_NONE
    private var configuredSpf: Int = 0

    /** Codec types for which EVERY platform decoder failed this session (no retry storm). */
    private val hwBlacklist: MutableSet<Int> = HashSet()
    /** Platform decoder names that failed this session; the next candidate is tried instead. */
    private val failedDecoders: MutableSet<String> = HashSet()
    /** Name of the running platform decoder (null = none). */
    private var activeDecoderName: String? = null
    /** Inputs queued since the decoder last produced PCM (no-output watchdog). */
    private var inputsWithoutOutput: Int = 0

    // --- A/V sync: PTS pacing anchor (matches VideoRenderer's PTS scheduling) ---
    // ptsNs is ntp_time_remote — same clock domain as video. We anchor the first
    // audio packet to System.nanoTime(), then pace subsequent writes so audio
    // doesn't drift ahead of (or fall behind) the video timeline.
    @Volatile private var ptsBaseNs: Long = 0
    @Volatile private var wallBaseNs: Long = 0
    @Volatile private var hasSyncAnchor: Boolean = false
    @Volatile private var consecutiveDrops: Int = 0
    private val playoutClock = PlayoutClock()
    /** PCM frames written to the current AudioTrack (for queued-duration estimates). */
    private var framesWritten: Long = 0L
    @Volatile private var lastAudioNs: Long = 0L
    private var paceDbgCount = 0

    /** True while audio packets are arriving (lets the video path hold frames for lip sync). */
    val isActive: Boolean
        get() = lastAudioNs != 0L && System.nanoTime() - lastAudioNs < ACTIVE_WINDOW_NS
    // Debug: count syncAudio calls for timeline diagnosis (temporary)
    private var syncDbgCount: Int = 0

    // --- volume -------------------------------------------------------------
    private var currentVolumeDb: Float = AudioGain.MAX_DB
    private var currentLinearGain: Float = 1.0f

    /**
     * "Sync sender volume" setting. `true` (default): the sender's volume slider sets our gain.
     * `false`: sender volume is ignored and we play at full gain (device buttons control volume).
     */
    @Volatile
    var followSenderVolume: Boolean = true
        set(value) {
            field = value
            codecLock.withLock { if (!released) applyGainLocked() }
        }

    @Volatile
    private var released: Boolean = false

    /** Reused PCM scratch buffer for the decoder drain (guarded by [codecLock]). */
    private var drainChunk: ByteArray = ByteArray(0)

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
        if (released) return
        codecLock.withLock {
            // Re-check under the lock: an in-flight packet must not resurrect the
            // codec/AudioTrack after release().
            if (released) return@withLock
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
            if (released) return@withLock
            currentVolumeDb = db.coerceIn(AudioGain.MIN_DB, AudioGain.MAX_DB)
            applyGainLocked()
        }
    }

    private fun applyGainLocked() {
        currentLinearGain = AudioGain.effectiveGain(currentVolumeDb, followSenderVolume)
        try {
            audioTrack?.setVolume(currentLinearGain)
            if (oboeHandle != 0L) NativeBridge.audioSetGain(oboeHandle, currentLinearGain)
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack.setVolume failed", t)
        }
    }

    /**
     * Drop all state and reset for a new session: tears down the active
     * decoder(s) and [AudioTrack] and clears the HW blacklist so the new
     * session re-probes the platform decoders.
     */
    fun reset() {
        codecLock.withLock {
            if (released) return@withLock
            teardownDecodersLocked()
            stopAndReleaseTrackLocked()
            hwBlacklist.clear()
            failedDecoders.clear()
            configuredCt = CT_NONE
            configuredSpf = 0
            hasSyncAnchor = false   // re-anchor on next session
            consecutiveDrops = 0
            Log.i(TAG, "reset()")
        }
    }

    /**
     * Sender FLUSH (seek / track skip / pause): drop everything decoded or queued
     * but keep the sink open. The decoder is rebuilt on the next packet (cheap, and
     * avoids MediaCodec's CSD-resubmit-after-flush caveat). Called on the rtp_audio
     * thread, ordered with [feedAudio]; [codecLock] guards against reset/release.
     * Adapted from jqssun/android-airplay-server PR #81 (GPL-3.0).
     */
    fun flush() {
        codecLock.withLock {
            if (released) return@withLock
            try {
                teardownDecodersLocked()
                if (oboeHandle != 0L) NativeBridge.audioFlush(oboeHandle)
                audioTrack?.let { t ->
                    t.pause()
                    t.flush()
                    t.play()
                    // queued = written - head; re-base so a flushed backlog isn't counted
                    framesWritten = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                }
            } catch (t: Throwable) {
                Log.w(TAG, "flush failed", t)
            }
            hasSyncAnchor = false
            consecutiveDrops = 0
            Log.i(TAG, "flush()")
        }
    }

    /** Idempotent teardown. Release the decoders, the AudioTrack, and the SW ALAC handle. */
    fun release() {
        codecLock.withLock {
            if (released) return@withLock
            released = true
            teardownDecodersLocked()
            stopAndReleaseTrackLocked()
            drainChunk = ByteArray(0)
            hwBlacklist.clear()
            failedDecoders.clear()
            configuredCt = CT_NONE
            configuredSpf = 0
            Log.i(TAG, "release()")
        }
    }

    // ---- internals: feed / route ------------------------------------------

    private fun feedAudioLocked(data: ByteArray, ct: Int, ptsNs: Long, seqNum: Int) {
        if (data.isEmpty() || released) return
        ensureConfiguredLocked(ct)
        ensureTrackLocked()
        currentPtsNs = ptsNs

        // A/V sync: pace or drop this packet to align with the PTS timeline.
        if (!paceAudio(ptsNs)) return   // dropped to maintain sync

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

    /**
     * Lip sync: play each packet at its slot on the shared [PlayoutClock] (same clock
     * the video path uses), accounting for audio already queued in the AudioTrack.
     * Falls back to the relative [syncAudio] pacing when no NTP mapping exists yet.
     */
    private fun paceAudio(ptsNs: Long): Boolean {
        lastAudioNs = System.nanoTime()
        // Oboe path: the native timeline buffer absorbs jitter and aligns to the sender
        // timeline itself (silence fill / late drop), so no sleep-based pacing here.
        if (oboeHandle != 0L) return true
        val delayNs = playoutClock.delayNs(ptsNs) ?: return syncAudio(ptsNs)
        val queuedNs = audioTrack?.let { queuedNsLocked(it) } ?: 0L
        val action = AvSyncPolicy.audioAction(delayNs, queuedNs)
        if (paceDbgCount < 40 || paceDbgCount % 512 == 0) {
            Log.d(TAG, "av sync: audio delay=${delayNs / 1_000_000}ms queued=${queuedNs / 1_000_000}ms -> $action")
        }
        paceDbgCount++
        return when (action) {
            AudioAction.Play -> true
            AudioAction.Drop -> false
            is AudioAction.Wait -> {
                try { Thread.sleep(action.ms) } catch (_: InterruptedException) { }
                true
            }
        }
    }

    private fun queuedNsLocked(track: AudioTrack): Long {
        val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queuedFrames = (framesWritten - played).coerceAtLeast(0L)
        return queuedFrames * 1_000_000_000L / SAMPLE_RATE
    }

    /** Make sure a decoder for [ct] exists; rebuild (and route to SW on failure). */
    private fun ensureConfiguredLocked(ct: Int) {
        if (ct == configuredCt && (codec != null || swAlacHandle != 0L)) return
        teardownDecodersLocked()
        setupCodecLocked(ct, configuredSpf)
        configuredCt = ct
    }

    /**
     * Instantiate a platform [MediaCodec] for [ct], trying every capable decoder in turn
     * ([AudioDecoderSelector]). Only when ALL of them failed is [ct] blacklisted for the
     * session; ALAC then falls back to the bundled software decoder.
     */
    private fun setupCodecLocked(ct: Int, samplesPerFrame: Int) {
        if (hwBlacklist.contains(ct)) {
            if (ct == CT_ALAC && swAlacEnabled) ensureSwAlacLocked()
            return
        }
        val mime = mimeFor(ct) ?: run {
            Log.w(TAG, "Unsupported ct=$ct; no HW decoder")
            return
        }
        val c = createHwCodecLocked(ct, mime)
        if (c != null) {
            codec = c
            inputsWithoutOutput = 0
            Log.i(TAG, "Decoder ready: $activeDecoderName (ct=$ct, spf=$samplesPerFrame)")
            return
        }
        Log.e(TAG, "No working platform decoder for ct=$ct (failed: $failedDecoders)")
        hwBlacklist.add(ct)
        if (ct == CT_ALAC) {
            if (swAlacEnabled) ensureSwAlacLocked() else Log.w(TAG, "ALAC HW failed and swAlacEnabled=false; no audio path")
        }
    }

    private fun mimeFor(ct: Int): String? = when (ct) {
        CT_AAC_ELD, CT_AAC_LC -> MediaFormat.MIMETYPE_AUDIO_AAC // "audio/mp4a-latm"
        CT_ALAC -> MIME_AUDIO_ALAC
        else -> null
    }

    /**
     * Try each candidate decoder (best first) until one configures + starts. Returns null when
     * every candidate failed. With an empty decoder list we fall back to createDecoderByType once.
     */
    private fun createHwCodecLocked(ct: Int, mime: String): MediaCodec? {
        val names = AudioDecoderSelector.order(AudioDecoderSelector.platformCandidates(mime), failedDecoders)
        if (names.isEmpty()) {
            if (DEFAULT_DECODER in failedDecoders) return null
            return startDecoderLocked(DEFAULT_DECODER, ct, mime)
        }
        for (name in names) {
            startDecoderLocked(name, ct, mime)?.let { return it }
        }
        return null
    }

    /** Create + configure + start one decoder; on failure remember [name] and return null. */
    private fun startDecoderLocked(name: String, ct: Int, mime: String): MediaCodec? {
        var c: MediaCodec? = null
        return try {
            c = if (name == DEFAULT_DECODER) MediaCodec.createDecoderByType(mime) else MediaCodec.createByCodecName(name)
            c.configure(buildFormat(ct, mime), null, null, 0)
            c.start()
            activeDecoderName = name
            c
        } catch (t: Throwable) {
            Log.w(TAG, "Decoder $name failed for ct=$ct (${t.javaClass.simpleName}: ${t.message})")
            failedDecoders.add(name)
            try { c?.release() } catch (_: Throwable) { /* best-effort */ }
            null
        }
    }

    private fun buildFormat(ct: Int, mime: String): MediaFormat =
        MediaFormat().apply {
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
                // A decoder that swallows input but never emits PCM (e.g. a vendor AAC decoder
                // that "accepts" ELD) counts as failed -> the next candidate is tried.
                if (++inputsWithoutOutput > NO_OUTPUT_LIMIT) {
                    throw IllegalStateException("decoder $activeDecoderName produced no output")
                }
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
                    if (info.size > 0) inputsWithoutOutput = 0
                    if (outBuf != null && info.size > 0 && (track != null || oboeHandle != 0L)) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        if (drainChunk.size < info.size) drainChunk = ByteArray(info.size)
                        outBuf.get(drainChunk, 0, info.size)
                        val outPtsNs = if (info.presentationTimeUs != 0L) info.presentationTimeUs * 1000L else currentPtsNs
                        writePcmLocked(drainChunk, info.size, outPtsNs)
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
        if (audioTrack == null && oboeHandle == 0L) return
        val pcm = try {
            NativeBridge.alacDecode(h, data)
        } catch (t: Throwable) {
            Log.e(TAG, "NativeBridge.alacDecode threw", t)
            ByteArray(0)
        }
        if (pcm.isNotEmpty()) writePcmLocked(pcm, pcm.size, currentPtsNs)
    }

    private fun writePcmLocked(pcm: ByteArray, length: Int, ptsNs: Long) {
        if (length <= 0) return
        if (oboeHandle != 0L) {
            try {
                NativeBridge.audioWrite(oboeHandle, pcm, length, ptsNs)
                if (++oboeWrites % OBOE_STATS_EVERY == 0L) logOboeStatsLocked()
            } catch (t: Throwable) {
                Log.w(TAG, "Oboe write failed", t)
            }
            return
        }
        val track = audioTrack ?: return
        try {
            val written = track.write(pcm, 0, length, AudioTrack.WRITE_BLOCKING)
            if (written > 0) framesWritten += written / BYTES_PER_FRAME
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack.write failed", t)
        }
    }

    // ---- internals: failure handling ---------------------------------------

    /**
     * Contain a MediaCodec failure: kill the codec and remember its name, so the next packet
     * sets up the next candidate decoder. [setupCodecLocked] blacklists [ct] (and arms SW ALAC)
     * only once every candidate has failed.
     */
    private fun onCodecFailureLocked(ct: Int) {
        val failed = activeDecoderName ?: DEFAULT_DECODER
        Log.e(TAG, "MediaCodec failure for ct=$ct on $failed; trying the next decoder")
        failedDecoders.add(failed)
        codec?.let { c ->
            try { c.stop() } catch (t: Throwable) { /* best-effort */ }
            try { c.release() } catch (t: Throwable) { /* best-effort */ }
        }
        codec = null
        activeDecoderName = null
    }

    // ---- internals: AudioTrack lifecycle -----------------------------------

    /** Try to bring up the Oboe sink; on any failure remember it and let AudioTrack take over. */
    private fun tryOpenOboeLocked(): Boolean {
        if (!nativeConfig.enabled || oboeUnavailable) return false
        val handle = try {
            NativeBridge.audioOpen(
                SAMPLE_RATE, nativeConfig.effectiveCushionMs, 0, true,
                deviceOutput.first, deviceOutput.second,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Oboe engine open threw", t)
            0L
        }
        if (AudioSinkSelector.select(nativeConfig, handle) != AudioSink.OBOE) {
            oboeUnavailable = true
            Log.w(TAG, "Oboe low-latency audio unavailable; falling back to AudioTrack")
            return false
        }
        oboeHandle = handle
        oboeWrites = 0L
        NativeBridge.audioSetGain(handle, currentLinearGain)
        Log.i(TAG, "Oboe low-latency audio active (cushion=" +
            (if (nativeConfig.effectiveCushionMs == 0) "auto" else "${nativeConfig.effectiveCushionMs}ms") + ")")
        return true
    }

    private fun logOboeStatsLocked() {
        val st = NativeBridge.audioStats(oboeHandle) ?: return
        if (st.size < 7) return
        Log.d(TAG, "oboe stats backlog=${st[0]}ms cushion=${st[1]}ms trims=${st[2]} drops=${st[3]} " +
            "silences=${st[4]} underruns=${st[5]} xruns=${st[6]}")
    }

    private fun closeOboeLocked() {
        val h = oboeHandle
        if (h == 0L) return
        oboeHandle = 0L
        try { NativeBridge.audioClose(h) } catch (t: Throwable) { Log.w(TAG, "Oboe close failed", t) }
    }

    private fun ensureTrackLocked() {
        if (audioTrack != null || oboeHandle != 0L) return
        if (tryOpenOboeLocked()) return

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
        activeDecoderName = null
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
        closeOboeLocked()
        audioTrack?.let { t ->
            try { t.pause() } catch (ign: Throwable) { /* best-effort */ }
            try { t.flush() } catch (ign: Throwable) { /* best-effort */ }
            try { t.release() } catch (ign: Throwable) { /* best-effort */ }
        }
        audioTrack = null
        framesWritten = 0L
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

        /** Pseudo decoder name for MediaCodec.createDecoderByType (empty MediaCodecList). */
        private const val DEFAULT_DECODER = "<default>"
        /** Queued inputs with no PCM out before a decoder counts as dead (~1-2 s of audio). */
        private const val NO_OUTPUT_LIMIT = 100

        // A/V sync thresholds (human perception of A/V drift is ~40–80 ms).
        private const val AHEAD_THRESHOLD_NS = 40_000_000L   // 40 ms ahead → sleep to pace
        private const val BEHIND_THRESHOLD_NS = 80_000_000L  // 80 ms behind → drop to catch up
        private const val LEAD_NS = 20_000_000L              // keep 20 ms lead after sleeping
        private const val ACTIVE_WINDOW_NS = 1_500_000_000L
        private const val BYTES_PER_FRAME = 4                  // PCM16 stereo
        private const val OBOE_STATS_EVERY = 1024L          // log Oboe timeline stats every N writes
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
