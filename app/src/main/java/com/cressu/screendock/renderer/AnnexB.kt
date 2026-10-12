package com.cressu.screendock.renderer

/**
 * Pure Annex-B NAL parsing helpers (no Android dependencies, JVM-testable).
 *
 * H.264 NAL type = `b & 0x1F`; H.265 NAL type = `(b >> 1) & 0x3F`.
 * Both 4-byte (`00 00 00 01`) and 3-byte (`00 00 01`) start codes are handled.
 */
internal object AnnexB {
    val AVC_KEY_TYPES = intArrayOf(5, 7)                  // IDR, SPS
    val HEVC_KEY_TYPES = intArrayOf(19, 20, 21, 32, 33)   // IDR/BLA, VPS, SPS
    val AVC_SPS = intArrayOf(7)
    val AVC_PPS = intArrayOf(8)
    val AVC_PARAM_TYPES = intArrayOf(7, 8)
    val HEVC_PARAM_TYPES = intArrayOf(32, 33, 34)         // VPS, SPS, PPS

    /** Offsets of every Annex-B start code, in order (4-byte preferred over 3-byte). */
    fun startCodePositions(data: ByteArray): List<Int> {
        val starts = ArrayList<Int>()
        val n = data.size
        var i = 0
        while (i + 2 < n) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts.add(i); i += 3; continue
                }
                if (i + 3 < n && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add(i); i += 4; continue
                }
            }
            i++
        }
        return starts
    }

    private fun startCodeLen(data: ByteArray, sc: Int): Int =
        if (data.size - sc >= 4 && data[sc + 2] == 0.toByte() && data[sc + 3] == 1.toByte()) 4 else 3

    private fun nalType(b: Int, isH265: Boolean): Int =
        if (isH265) (b shr 1) and 0x3F else b and 0x1F

    /** True if any NAL in [data] is a keyframe indicator (IDR or parameter set). */
    fun isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        val keyTypes = if (isH265) HEVC_KEY_TYPES else AVC_KEY_TYPES
        for (sc in startCodePositions(data)) {
            val nalStart = sc + startCodeLen(data, sc)
            if (nalStart >= data.size) continue
            if (keyTypes.contains(nalType(data[nalStart].toInt() and 0xFF, isH265))) return true
        }
        return false
    }

    /**
     * Concatenate the NAL units of the requested [types] (including their start
     * codes) from [data], or `null` if none are present.
     */
    fun extractNals(data: ByteArray, types: IntArray, isH265: Boolean): ByteArray? {
        val starts = startCodePositions(data)
        val out = java.io.ByteArrayOutputStream()
        var found = false
        for (idx in starts.indices) {
            val sc = starts[idx]
            val nalStart = sc + startCodeLen(data, sc)
            val nalEnd = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            if (nalStart >= nalEnd) continue
            if (types.contains(nalType(data[nalStart].toInt() and 0xFF, isH265))) {
                found = true
                out.write(data, sc, nalEnd - sc)
            }
        }
        return if (found) out.toByteArray() else null
    }

    /** All parameter-set NALs (SPS/PPS, or VPS/SPS/PPS) in [data], or `null`. */
    fun paramSets(data: ByteArray, isH265: Boolean): ByteArray? =
        extractNals(data, if (isH265) HEVC_PARAM_TYPES else AVC_PARAM_TYPES, isH265)
}
