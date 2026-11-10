package com.cressu.screendock.renderer

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

private const val TAG = "AirplayVideo"

/**
 * Queries MediaCodecList for the decoders mirroring uses. Decisions live in [DecoderSizing].
 * Adapted from jqssun/android-airplay-server PR #31 (GPL-3.0).
 */
object DecoderCaps {
    const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    /** True when any decoder (hardware or software) handles H.265. */
    fun hasDecoder(mime: String): Boolean = decoders(mime, MediaCodecList.ALL_CODECS).isNotEmpty()

    /**
     * Cap a wanted mirroring size to what the decoder(s) the sender may use can decode.
     * H.264 is always considered; H.265 only when it will be advertised.
     */
    fun capAdvertisedSize(width: Int, height: Int, h265: Boolean): Pair<Int, Int> {
        val mimes = listOfNotNull(MIME_AVC, MIME_HEVC.takeIf { h265 })
        val caps = mimes.map { defaultVideoCaps(it) }
        val limits = DecoderSizing.combine(
            caps.map { c -> c?.let { DecoderSizing.Limits(it.supportedWidths.upper, it.supportedHeights.upper) } },
        )
        val sizeOk: (Int, Int) -> Boolean = { w, h ->
            caps.all { c -> c == null || runCatching { c.isSizeSupported(w, h) }.getOrDefault(true) }
        }
        val out = DecoderSizing.capToDecoder(width, height, limits, sizeOk)
        if (out != width to height) Log.i(TAG, "advertised size capped to decoder: ${width}x$height -> ${out.first}x${out.second}")
        return out
    }

    /** Name of the decoder createDecoderByType would pick (first regular decoder for [mime]). */
    fun defaultDecoderName(mime: String): String? = decoders(mime, MediaCodecList.REGULAR_CODECS).firstOrNull()?.name

    /** Other decoders to try when the default cannot start (see [DecoderSizing.fallbackOrder]). */
    fun fallbackDecoders(mime: String, failedName: String?): List<String> =
        DecoderSizing.fallbackOrder(
            decoders(mime, MediaCodecList.ALL_CODECS).map {
                DecoderSizing.Candidate(it.name, softwareOnly(it))
            },
            failedName,
        )

    private fun defaultVideoCaps(mime: String): MediaCodecInfo.VideoCapabilities? = try {
        decoders(mime, MediaCodecList.REGULAR_CODECS).firstOrNull()
            ?.getCapabilitiesForType(mime)?.videoCapabilities
    } catch (t: Throwable) {
        Log.w(TAG, "decoder capabilities unavailable for $mime", t)
        null
    }

    private fun decoders(mime: String, kind: Int): List<MediaCodecInfo> = try {
        MediaCodecList(kind).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "MediaCodecList query failed for $mime", t)
        emptyList()
    }

    private fun softwareOnly(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly
        else DecoderSizing.isSoftwareName(info.name)
}
