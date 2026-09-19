package com.cressu.screendock.bridge

/**
 * Native → Kotlin callback surface. JNI descriptors are frozen and MUST match
 * `android_raop_callbacks.c` exactly (see `docs/DESIGN.md §5.2`).
 *
 * Implemented by [com.cressu.screendock.service.AirPlayService] from M1; for M0
 * the MainActivity provides a no-op implementation to validate the linkage.
 *
 * **Threading**: every method here is invoked on a UxPlay native pthread
 * (httpd / rtp_video / rtp_audio / ntp), NOT a JVM thread. Implementations must
 * be thread-safe; UI updates must hop to the main looper.
 */
interface RaopCallbackHandler {

    // ---- lifecycle ----
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)

    // ---- video: data = one or more Annex-B NALUs (00 00 00 01 prefixed), decrypted ----
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
    fun onVideoSize(w: Float, h: Float, par: Float, rotation: Float)

    // ---- audio: data = one AAC-ELD/LC frame or ALAC packet (decrypted) ----
    // ct: 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD
    fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int)
    fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean)
    fun onVolumeChange(volume: Float)

    // ---- PIN (v2) — stubbed for v1 ----
    fun onDisplayPin(pin: String)
}
