package com.cressu.screendock.renderer

import org.junit.Assert.*
import org.junit.Test

class AnnexBTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val sc4 = b(0, 0, 0, 1)
    private val sc3 = b(0, 0, 1)

    @Test fun avcIdrWith4ByteStartCodeIsKeyframe() =
        assertTrue(AnnexB.isKeyframe(sc4 + b(0x65, 1, 2), false))

    @Test fun avcSliceWith3ByteStartCodeIsNotKeyframe() =
        assertFalse(AnnexB.isKeyframe(sc3 + b(0x41, 1, 2), false))

    @Test fun avcSpsWith3ByteStartCodeIsKeyframe() =
        assertTrue(AnnexB.isKeyframe(sc3 + b(0x67, 1), false))

    @Test fun hevcIdrAndVps() {
        assertTrue(AnnexB.isKeyframe(sc4 + b(19 shl 1, 1), true))
        assertTrue(AnnexB.isKeyframe(sc4 + b(32 shl 1, 1), true))
        assertFalse(AnnexB.isKeyframe(sc4 + b(1 shl 1, 1), true))
    }

    @Test fun extractsSpsAndPpsSeparately() {
        val data = sc4 + b(0x67, 9, 9) + sc3 + b(0x68, 8) + sc4 + b(0x65, 7, 7)
        assertArrayEquals(sc4 + b(0x67, 9, 9), AnnexB.extractNals(data, AnnexB.AVC_SPS, false))
        assertArrayEquals(sc3 + b(0x68, 8), AnnexB.extractNals(data, AnnexB.AVC_PPS, false))
        assertArrayEquals(sc4 + b(0x67, 9, 9) + sc3 + b(0x68, 8), AnnexB.paramSets(data, false))
    }

    @Test fun extractReturnsNullWhenAbsent() =
        assertNull(AnnexB.extractNals(sc4 + b(0x65, 1), AnnexB.AVC_SPS, false))

    @Test fun hevcParamSets() {
        val data = sc4 + b(32 shl 1, 1) + sc4 + b(33 shl 1, 2) + sc4 + b(34 shl 1, 3) + sc4 + b(19 shl 1, 4)
        assertArrayEquals(
            sc4 + b(32 shl 1, 1) + sc4 + b(33 shl 1, 2) + sc4 + b(34 shl 1, 3),
            AnnexB.paramSets(data, true),
        )
    }

    @Test fun startCodePositionsMixed() {
        val data = sc4 + b(0x67, 1) + sc3 + b(0x68)
        assertEquals(listOf(0, 6), AnnexB.startCodePositions(data))
    }

    @Test fun emptyAndGarbage() {
        assertFalse(AnnexB.isKeyframe(ByteArray(0), false))
        assertFalse(AnnexB.isKeyframe(b(1, 2, 3, 4, 5), false))
        assertTrue(AnnexB.startCodePositions(b(0, 0)).isEmpty())
    }
}
