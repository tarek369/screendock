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

    // ---- PIN ----
    /** Called when a sender starts PIN pairing; [pin] is the 4-digit code the user must type on the sender. */
    fun onDisplayPin(pin: String)

    // ---- admission ----
    /**
     * A sender is asking to connect. Return false to refuse it (it gets no stream).
     * Runs on UxPlay's httpd thread, so it MAY BLOCK briefly (e.g. while waiting for an
     * "ask before allowing" answer); do not block longer than ~10 s. Exceptions admit.
     * Descriptor: (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z
     */
    fun onClientRequest(deviceId: String, model: String, name: String): Boolean

    // ---- audio-only (music) metadata ----
    /** DMAP track info parsed natively (minm/asar/asal); any field may be null. */
    fun onAudioMetadata(title: String?, artist: String?, album: String?)
    /** Cover art bytes (JPEG or PNG). */
    fun onCoverArt(data: ByteArray)
    /** Playback progress, converted from RTP timestamps (44.1 kHz). */
    fun onAudioProgress(positionSec: Float, durationSec: Float)

    // ---- AirPlay video (HLS / YouTube etc.) ----
    /**
     * Start playing [location] in a local player (Media3/ExoPlayer HlsMediaSource works as-is).
     *
     * [location] is `http://localhost:<raop-port>/master.m3u8`, served by UxPlay's own httpd:
     * the sender hands us the playlists (via reverse "FCUP" requests) and UxPlay rewrites them
     * to this loopback URL; the media segment URLs inside are the real CDN https URLs, which the
     * player fetches directly. The URL is valid only while the sender's session is alive and is
     * plain http, so the app must permit cleartext to localhost (network security config).
     *
     * Must return quickly (post to your player thread). Native then waits up to 10 s for the
     * player to push its first ready state via [NativeBridge.updateHlsPlaybackInfo] so the sender
     * gets a real duration. Runs on the httpd thread.
     */
    fun onHlsPlay(location: String, startPositionSec: Float)
    /** Stop and release the video player. May be called when no video is playing (idempotent). */
    fun onHlsStop()
    fun onHlsScrub(positionSec: Float)
    /** rate 1.0 = play, 0.0 = pause. */
    fun onHlsRate(rate: Float)

    /**
     * DEPRECATED / unused by native: playback info is now PUSHED with
     * [NativeBridge.updateHlsPlaybackInfo] (every ~250 ms and on state changes). Kept only so
     * implementers that already override it still compile.
     */
    fun getHlsPlaybackInfo(): FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
}
