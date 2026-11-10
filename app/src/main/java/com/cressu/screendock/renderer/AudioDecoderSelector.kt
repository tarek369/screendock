package com.cressu.screendock.renderer

import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/** One platform decoder that claims to support a MIME type. */
data class DecoderCandidate(val name: String, val hardware: Boolean)

/**
 * Picks the order in which [AudioRenderer] tries platform audio decoders.
 *
 * `MediaCodec.createDecoderByType` only ever returns the first decoder for a MIME type, so a
 * vendor AAC decoder that rejects AAC-ELD (or dies mid-stream) used to leave the whole session
 * silent. We instead walk every decoder that supports the MIME: hardware first (platform order
 * kept), then other software decoders, then the always-present Google software decoders.
 * Idea adapted from jqssun/android-airplay-server PR #45 (GPL-3.0), without FFmpeg.
 *
 * The ordering logic is pure (no Android types) so it is unit-tested on the JVM.
 */
object AudioDecoderSelector {
    private const val TAG = "AirplayAudio"

    /** Google's software AAC decoders: tried last because they are the known-good safety net. */
    val SOFTWARE_FALLBACKS = listOf("c2.android.aac.decoder", "OMX.google.aac.decoder")

    private val SOFTWARE_PREFIXES = listOf("omx.google.", "c2.android.")

    /** Name heuristic for API < 29, where MediaCodecInfo.isHardwareAccelerated does not exist. */
    fun looksSoftware(name: String): Boolean {
        val n = name.lowercase()
        return SOFTWARE_PREFIXES.any { n.startsWith(it) } || n.contains(".sw.") || n.endsWith(".sw")
    }

    /**
     * Decoder names to try, best first, skipping [exclude] (decoders that already failed this
     * session) and duplicate names. Empty when nothing is left to try.
     */
    fun order(candidates: List<DecoderCandidate>, exclude: Set<String> = emptySet()): List<String> {
        val usable = candidates.distinctBy { it.name.lowercase() }.filter { it.name !in exclude }
        val fallbackRank = { c: DecoderCandidate ->
            SOFTWARE_FALLBACKS.indexOfFirst { it.equals(c.name, ignoreCase = true) }
        }
        val hardware = usable.filter { it.hardware && fallbackRank(it) < 0 }
        val otherSoftware = usable.filter { !it.hardware && fallbackRank(it) < 0 }
        val fallbacks = usable.filter { fallbackRank(it) >= 0 }.sortedBy(fallbackRank)
        return (hardware + otherSoftware + fallbacks).map { it.name }
    }

    /** Every decoder on this device that supports [mime] (encoders and aliases excluded). */
    fun platformCandidates(mime: String): List<DecoderCandidate> = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .filter { info -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !info.isAlias }
            .map { info ->
                val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated && !info.isSoftwareOnly
                } else {
                    !looksSoftware(info.name)
                }
                DecoderCandidate(info.name, hw)
            }
    } catch (t: Throwable) {
        Log.w(TAG, "MediaCodecList query failed for $mime", t)
        emptyList()
    }
}
