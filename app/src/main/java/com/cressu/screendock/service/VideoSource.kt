package com.cressu.screendock.service

import java.net.URI

/**
 * Pure helpers for AirPlay video playback of arbitrary sender-supplied URLs (AirPlay Video V2:
 * "Content-Location" is a real http(s) HLS playlist or progressive file, often served by the
 * sender itself on the LAN). No Android dependencies, so they are unit tested on the JVM.
 */

/** Container family of a video URL, used to pick the MediaSource / MIME type. */
enum class VideoKind { HLS, PROGRESSIVE, UNKNOWN }

object VideoSource {
    private val PROGRESSIVE_EXT = setOf(
        "mp4", "m4v", "mov", "mp3", "m4a", "aac", "wav", "flac", "ts", "mkv", "webm", "3gp", "caf",
    )

    /** HLS if the path (or the query, for redirectors like `?u=a.m3u8`) names an .m3u8; progressive by extension. */
    fun classify(url: String): VideoKind {
        val uri = parse(url) ?: return VideoKind.UNKNOWN
        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".m3u") || uri.query.orEmpty().lowercase().contains(".m3u8")) {
            return VideoKind.HLS
        }
        val ext = path.substringAfterLast('.', "")
        return if (ext in PROGRESSIVE_EXT) VideoKind.PROGRESSIVE else VideoKind.UNKNOWN
    }

    /**
     * Policy for what the receiver will open: https anywhere, plain http ONLY to local hosts
     * (loopback for UxPlay's HLS proxy, RFC1918 / link-local / unique-local addresses, `.local`
     * mDNS names and single-label LAN hostnames). Android's network-security-config cannot express
     * CIDR ranges, so cleartext is enabled globally there and narrowed to the LAN here.
     */
    fun isAllowed(url: String): Boolean {
        val uri = parse(url) ?: return false
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> isLocalHost(host)
            else -> false
        }
    }

    internal fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return true
        if (':' in host) return isLocalIpv6(host)
        parseIpv4(host)?.let { return isPrivateIpv4(it) }
        return !host.contains('.') // single-label LAN hostname, e.g. "macbook"
    }

    private fun parse(url: String): URI? =
        try {
            URI(url.trim()).takeIf { !it.isOpaque }
        } catch (_: Exception) {
            null
        }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255 || parts[i].length > 3) return null
            out[i] = n
        }
        return out
    }

    private fun isPrivateIpv4(a: IntArray): Boolean =
        a[0] == 127 || a[0] == 10 ||
            (a[0] == 172 && a[1] in 16..31) ||
            (a[0] == 192 && a[1] == 168) ||
            (a[0] == 169 && a[1] == 254)

    private fun isLocalIpv6(host: String): Boolean {
        val h = host.substringBefore('%') // zone id
        if (h == "::1") return true
        val first = h.substringBefore(':').toIntOrNull(16) ?: return false
        return (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00
    }
}

/**
 * Start position of a video, as carried by the (frozen) native up-call `onHlsPlay(url, float)`:
 * a value >= 0 is an absolute position in SECONDS; a value < 0 is a FRACTION (0..1) of the item's
 * duration encoded as `-fraction` (AirPlay V2 "Start-Position"). See lib/video_v2.h.
 */
sealed interface StartPosition {
    data class Seconds(val seconds: Double) : StartPosition
    data class Fraction(val fraction: Double) : StartPosition

    /** Milliseconds to start at, or null if a fraction cannot be resolved yet (duration unknown). */
    fun resolveMs(durationMs: Long?): Long? = when (this) {
        is Seconds -> (seconds * 1000.0).toLong()
        is Fraction -> if (durationMs != null && durationMs > 0) (fraction * durationMs).toLong() else null
    }

    companion object {
        val ZERO: StartPosition = Seconds(0.0)

        fun decode(nativeValue: Float): StartPosition {
            if (nativeValue.isNaN() || nativeValue.isInfinite() || nativeValue == 0f) return ZERO
            return if (nativeValue > 0f) Seconds(nativeValue.toDouble())
            else Fraction((-nativeValue).toDouble().coerceAtMost(1.0))
        }
    }
}
