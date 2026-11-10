package com.cressu.screendock.renderer

/**
 * Pure decoder-capability decisions (unit-tested; no Android types).
 *
 * Adapted from jqssun/android-airplay-server PR #31 (GPL-3.0): strict hardware decoders
 * (e.g. Adreno 610, Mali-470 TV chips) reject streams beyond their real limits and stay black,
 * so the advertised mirroring size is capped to what the decoder supports, and a failed
 * decoder start falls back to other decoders for the same format.
 */
object DecoderSizing {

    /** Upper bounds a decoder reports for one MIME type. */
    data class Limits(val maxWidth: Int, val maxHeight: Int)

    /** One decoder from MediaCodecList, reduced to what the fallback order needs. */
    data class Candidate(val name: String, val softwareOnly: Boolean)

    /** Used when a decoder's limits are unknown: 1080p is decodable on every device. */
    val SAFE_DEFAULT = Limits(1920, 1080)

    private const val SHRINK_NUM = 9
    private const val SHRINK_DEN = 10
    private const val MAX_SHRINK_STEPS = 12
    private const val MIN_EDGE = 320

    /**
     * Tightest limits across the decoders the sender may pick (H.264 always, H.265 when
     * advertised). An unknown entry (null) counts as [SAFE_DEFAULT].
     */
    fun combine(limits: List<Limits?>): Limits {
        if (limits.isEmpty()) return SAFE_DEFAULT
        return limits.map { it ?: SAFE_DEFAULT }
            .reduce { a, b -> Limits(minOf(a.maxWidth, b.maxWidth), minOf(a.maxHeight, b.maxHeight)) }
    }

    /**
     * Scale [width]x[height] down (never up), keeping the aspect ratio, until it fits [limits]
     * and [sizeOk] accepts it (decoders can have area/alignment limits beyond max width/height).
     * Results are even. Inputs <= 0 are returned unchanged.
     */
    fun capToDecoder(
        width: Int,
        height: Int,
        limits: Limits,
        sizeOk: (Int, Int) -> Boolean = { _, _ -> true },
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        var (w, h) = fit(width, height, limits.maxWidth, limits.maxHeight)
        var steps = 0
        while (!sizeOk(w, h) && steps < MAX_SHRINK_STEPS && minOf(w, h) > MIN_EDGE) {
            w = even(w.toLong() * SHRINK_NUM / SHRINK_DEN)
            h = even(h.toLong() * SHRINK_NUM / SHRINK_DEN)
            steps++
        }
        return w to h
    }

    /** Largest even size with the same aspect that fits in maxW x maxH (no upscaling). */
    fun fit(width: Int, height: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (maxW <= 0 || maxH <= 0) return even(width.toLong()) to even(height.toLong())
        if (width <= maxW && height <= maxH) return even(width.toLong()) to even(height.toLong())
        // Compare width/maxW vs height/maxH without floating point.
        return if (width.toLong() * maxH >= height.toLong() * maxW) {
            even(maxW.toLong()) to even(height.toLong() * maxW / width)
        } else {
            even(width.toLong() * maxH / height) to even(maxH.toLong())
        }
    }

    /**
     * Decoders to try after the default one ([failedName]) could not start: other hardware
     * decoders first, then software ones (c2.android.* / OMX.google.*). Order within each group
     * follows MediaCodecList (the platform's preference order).
     */
    fun fallbackOrder(candidates: List<Candidate>, failedName: String?): List<String> {
        val rest = candidates.filter { !it.name.equals(failedName, ignoreCase = true) }.distinctBy { it.name }
        val (sw, hw) = rest.partition { it.softwareOnly || isSoftwareName(it.name) }
        return hw.map { it.name } + sw.map { it.name }
    }

    /** Name-based software detection for API < 29 (no MediaCodecInfo.isSoftwareOnly). */
    fun isSoftwareName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") ||
            (!n.startsWith("omx.") && !n.startsWith("c2."))
    }

    private fun even(v: Long): Int = (v.toInt() and 1.inv()).coerceAtLeast(2)
}
