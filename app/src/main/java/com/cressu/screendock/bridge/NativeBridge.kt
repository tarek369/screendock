package com.cressu.screendock.bridge

/**
 * Thin JNI surface over the UxPlay C core. The native side lives in
 * `app/src/main/cpp/native_bridge.cpp`; descriptors are frozen in
 * `docs/DESIGN.md §5.1`.
 *
 * M0 only exercises [versionString] and [selfTest] — the rest of the surface is
 * declared now so the contract is stable, but is wired up from M1 onward.
 *
 * The opaque `Long` handle returned by [init] is a `server_ctx_t*` on the native
 * side; it must be passed to every downward call and released via [destroy].
 */
object NativeBridge {

    @Volatile private var loaded: Boolean = false

    init {
        loaded = try {
            System.loadLibrary("airplay_native")
            true
        } catch (t: UnsatisfiedLinkError) {
            // Surfaced to the UI via isLoaded(); the Activity shows a clear error.
            false
        }
    }

    /** True if libairplay_native.so loaded successfully. */
    fun isLoaded(): Boolean = loaded

    // ---- M0: version + self-test ------------------------------------------

    /** Human-readable build string, e.g. "ScreenDock 0.1.0 (UxPlay core 220.68)". */
    fun versionString(): String =
        if (loaded) nativeGetVersion() else "native library not loaded"

    /** Round-trips raop_init/dnssd_init to confirm symbols resolve. M0 health check. */
    fun selfTest(): Boolean = if (loaded) nativeSelfTest() else false

    // ---- Full surface (stable contract; M1+) ------------------------------
    // Kept here so the Kotlin<->C signature pair is reviewable in one place.

    /** [name] is the receiver's display name: used as the mDNS/RAOP instance name at [start]. */
    fun init(
        handler: RaopCallbackHandler,
        hwAddr: ByteArray,
        name: String,
        keyFile: String,
        requirePin: Boolean,
    ): Long = nativeInit(handler, hwAddr, name, keyFile, requirePin)

    fun destroy(handle: Long) = nativeDestroy(handle)

    @Suppress("unused") // exercised from M1
    fun start(handle: Long, requestedPort: Int): Int = nativeStart(handle, requestedPort)

    @Suppress("unused") // exercised from M1
    fun stop(handle: Long) = nativeStop(handle)

    @Suppress("unused") // exercised from M1
    fun setDisplaySize(handle: Long, w: Int, h: Int, fps: Int) =
        nativeSetDisplaySize(handle, w, h, fps)

    // ---- discovery (M1): TXT records + service name for NsdManager --------

    /** RAOP TXT records (key→value), e.g. cn, et, ft, pk, … */
    fun getRaopTxtRecords(handle: Long): Map<String, String> = nativeGetRaopTxtRecords(handle)

    /** AirPlay TXT records (key→value), e.g. deviceid, features, pk, … */
    fun getAirplayTxtRecords(handle: Long): Map<String, String> = nativeGetAirplayTxtRecords(handle)

    /** RAOP instance name "<hexmac>@<name>" (NsdManager registers this as the service name). */
    fun getRaopServiceName(handle: Long): String = nativeGetRaopServiceName(handle)

    // ---- PIN + HLS (call BEFORE [start]; changing them later needs a restart) ----

    /**
     * Require a PIN on the sender. null/empty = no PIN. A 4-digit string (e.g. "1234") = fixed PIN.
     * Any other non-empty value is rejected (treated as no PIN, logged). With [init]'s
     * requirePin=true and no fixed PIN, a random 4-digit PIN is generated per pairing and reported
     * through [RaopCallbackHandler.onDisplayPin]; a fixed PIN is also reported there.
     * Advertises the PIN flags in the mDNS TXT records.
     */
    fun setPin(handle: Long, pin: String?) = nativeSetPin(handle, pin)

    /** Enable AirPlay video (HLS / YouTube): advertises the video feature bits and accepts /play. */
    fun setHlsEnabled(handle: Long, enabled: Boolean) = nativeSetHlsEnabled(handle, enabled)

    /**
     * Push the local video player's state; native answers the sender's ~1 Hz /playback-info from
     * this snapshot. Call every ~250 ms while a video plays and on every state change.
     * @param durationSec total seconds (0 if unknown yet, NEVER negative while playing)
     * @param positionSec current position
     * @param rate 1f playing, 0f paused/buffering
     * @param ready true once the player is prepared and the duration is known
     * Pass durationSec = -1 when playback ended or failed (sender then ends the session).
     */
    fun updateHlsPlaybackInfo(handle: Long, durationSec: Double, positionSec: Double, rate: Float, ready: Boolean) {
        if (loaded && handle != 0L) nativeUpdateHlsPlaybackInfo(handle, durationSec, positionSec, rate, ready)
    }

    // ---- caps (M1) --------------------------------------------------------

    /** Toggle H.265 advertisement (features bit 42 SupportsScreenMultiCodec). */
    fun setH265Enabled(handle: Long, enabled: Boolean) = nativeSetH265Enabled(handle, enabled)

    /** Set advertised codecs: ALAC + AAC in the `cn` TXT list. */
    fun setCodecs(handle: Long, alac: Boolean, aac: Boolean) = nativeSetCodecs(handle, alac, aac)

    // ---- software ALAC fallback (M3) --------------------------------------
    // Builds ALACSpecificConfig + creates an ALACDecoder on the native side.

    fun alacInit(
        frameLength: Int, numChannels: Int, bitDepth: Int,
        pb: Int, mb: Int, kb: Int,
    ): Long = nativeAlacInit(frameLength, numChannels, bitDepth, pb, mb, kb)

    fun alacDecode(handle: Long, input: ByteArray): ByteArray = nativeAlacDecode(handle, input)
    fun alacDestroy(handle: Long) = nativeAlacDestroy(handle)

    // ---- touch backchannel (receiver → sender HID) ------------------------

    /** Touch phases for [sendTouchEvent]. */
    const val TOUCH_START = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_END = 2

    /**
     * Inject a touch event into the sender over the AirPlay event channel.
     * Returns false when no sender is connected to the channel.
     *
     * @param phase [TOUCH_START], [TOUCH_MOVE] or [TOUCH_END].
     * @param xNorm Normalized x [0..1] across the sender's screen (top-left origin).
     * @param yNorm Normalized y [0..1].
     */
    fun sendTouchEvent(handle: Long, phase: Int, xNorm: Double, yNorm: Double): Boolean =
        if (loaded) nativeSendTouchEvent(handle, phase, xNorm, yNorm) else false

    // ---- native declarations (must match native_bridge.cpp) ---------------

    @JvmStatic private external fun nativeGetVersion(): String
    @JvmStatic private external fun nativeSelfTest(): Boolean

    @JvmStatic private external fun nativeInit(
        handler: RaopCallbackHandler,
        hwAddr: ByteArray,
        name: String,
        keyFile: String,
        requirePin: Boolean,
    ): Long

    @JvmStatic private external fun nativeDestroy(handle: Long)

    @JvmStatic private external fun nativeSetPin(handle: Long, pin: String?)
    @JvmStatic private external fun nativeSetHlsEnabled(handle: Long, enabled: Boolean)
    @JvmStatic private external fun nativeUpdateHlsPlaybackInfo(
        handle: Long, durationSec: Double, positionSec: Double, rate: Float, ready: Boolean,
    )

    @JvmStatic private external fun nativeStart(handle: Long, requestedPort: Int): Int
    @JvmStatic private external fun nativeStop(handle: Long)
    @JvmStatic private external fun nativeSetDisplaySize(handle: Long, w: Int, h: Int, fps: Int)

    @JvmStatic private external fun nativeGetRaopTxtRecords(handle: Long): Map<String, String>
    @JvmStatic private external fun nativeGetAirplayTxtRecords(handle: Long): Map<String, String>
    @JvmStatic private external fun nativeGetRaopServiceName(handle: Long): String
    @JvmStatic private external fun nativeSetH265Enabled(handle: Long, enabled: Boolean)
    @JvmStatic private external fun nativeSetCodecs(handle: Long, alac: Boolean, aac: Boolean)

    @JvmStatic private external fun nativeAlacInit(
        frameLength: Int, numChannels: Int, bitDepth: Int,
        pb: Int, mb: Int, kb: Int,
    ): Long

    @JvmStatic private external fun nativeAlacDecode(handle: Long, input: ByteArray): ByteArray
    @JvmStatic private external fun nativeAlacDestroy(handle: Long)

    @JvmStatic private external fun nativeSendTouchEvent(
        handle: Long, phase: Int, xNorm: Double, yNorm: Double,
    ): Boolean
}
