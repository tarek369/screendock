package com.cressu.screendock.renderer

import com.cressu.screendock.renderer.DecoderSizing.Candidate
import com.cressu.screendock.renderer.DecoderSizing.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderSizingTest {

    @Test fun `size within limits is unchanged`() {
        assertEquals(1920 to 1080, DecoderSizing.capToDecoder(1920, 1080, Limits(3840, 2160)))
    }

    @Test fun `width-bound size scales down keeping aspect`() {
        // 3392x2400 tablet panel on a 1920x1088 decoder
        val (w, h) = DecoderSizing.capToDecoder(3392, 2400, Limits(1920, 1088))
        assertTrue(w <= 1920 && h <= 1088)
        assertEquals(3392.0 / 2400.0, w.toDouble() / h, 0.01)
    }

    @Test fun `height-bound size scales by height`() {
        assertEquals(1440 to 1080, DecoderSizing.fit(2880, 2160, 4096, 1080))
    }

    @Test fun `results are even`() {
        val (w, h) = DecoderSizing.fit(2001, 1001, 1001, 1001)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test fun `never upscales`() {
        assertEquals(1280 to 720, DecoderSizing.fit(1280, 720, 3840, 2160))
    }

    @Test fun `shrinks further until decoder accepts the size`() {
        val (w, h) = DecoderSizing.capToDecoder(3840, 2160, Limits(4096, 4096)) { a, b -> a * b <= 1920 * 1080 }
        assertTrue(w * h <= 1920 * 1080)
        assertEquals(16.0 / 9.0, w.toDouble() / h, 0.02)
    }

    @Test fun `shrink loop is bounded when nothing is accepted`() {
        val (w, h) = DecoderSizing.capToDecoder(3840, 2160, Limits(4096, 4096)) { _, _ -> false }
        assertTrue(w > 0 && h > 0)
    }

    @Test fun `non-positive input is returned unchanged`() {
        assertEquals(0 to 0, DecoderSizing.capToDecoder(0, 0, Limits(1920, 1080)))
    }

    @Test fun `combine takes the tightest limits and treats unknown as 1080p`() {
        assertEquals(Limits(1920, 1080), DecoderSizing.combine(listOf(Limits(4096, 2304), null)))
        assertEquals(Limits(3840, 2160), DecoderSizing.combine(listOf(Limits(4096, 2160), Limits(3840, 4096))))
        assertEquals(DecoderSizing.SAFE_DEFAULT, DecoderSizing.combine(emptyList()))
    }

    @Test fun `fallback order puts other hardware first, software last, skips failed`() {
        val order = DecoderSizing.fallbackOrder(
            listOf(
                Candidate("c2.qti.avc.decoder", false),
                Candidate("c2.android.avc.decoder", true),
                Candidate("c2.mtk.avc.decoder", false),
                Candidate("OMX.google.h264.decoder", false), // software by name on old APIs
            ),
            failedName = "c2.qti.avc.decoder",
        )
        assertEquals(listOf("c2.mtk.avc.decoder", "c2.android.avc.decoder", "OMX.google.h264.decoder"), order)
    }

    @Test fun `software name detection`() {
        assertTrue(DecoderSizing.isSoftwareName("OMX.google.h264.decoder"))
        assertTrue(DecoderSizing.isSoftwareName("c2.android.hevc.decoder"))
        assertFalse(DecoderSizing.isSoftwareName("OMX.qcom.video.decoder.avc"))
        assertFalse(DecoderSizing.isSoftwareName("c2.exynos.h264.decoder"))
    }
}
