package com.cressu.screendock.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecoderSelectorTest {

    private fun hw(name: String) = DecoderCandidate(name, hardware = true)
    private fun sw(name: String) = DecoderCandidate(name, hardware = false)

    @Test
    fun `hardware first, then other software, then google fallbacks`() {
        val order = AudioDecoderSelector.order(
            listOf(
                sw("OMX.google.aac.decoder"),
                sw("c2.android.aac.decoder"),
                hw("OMX.vendor.aac.decoder"),
                sw("c2.vendor.aac.decoder.sw"),
                hw("c2.vendor.aac.decoder"),
            ),
        )
        assertEquals(
            listOf(
                "OMX.vendor.aac.decoder", "c2.vendor.aac.decoder", // hw, platform order kept
                "c2.vendor.aac.decoder.sw",                          // other software
                "c2.android.aac.decoder", "OMX.google.aac.decoder",  // known-good fallbacks last
            ),
            order,
        )
    }

    @Test
    fun `failed decoders are skipped`() {
        val order = AudioDecoderSelector.order(
            listOf(hw("OMX.vendor.aac.decoder"), sw("c2.android.aac.decoder")),
            exclude = setOf("OMX.vendor.aac.decoder"),
        )
        assertEquals(listOf("c2.android.aac.decoder"), order)
    }

    @Test
    fun `everything failed gives empty list`() {
        val order = AudioDecoderSelector.order(
            listOf(sw("c2.android.aac.decoder")),
            exclude = setOf("c2.android.aac.decoder"),
        )
        assertTrue(order.isEmpty())
    }

    @Test
    fun `duplicate names are tried once`() {
        val order = AudioDecoderSelector.order(listOf(hw("c2.x.aac"), hw("C2.X.AAC")))
        assertEquals(listOf("c2.x.aac"), order)
    }

    @Test
    fun `fallback flagged as hardware still goes last`() {
        val order = AudioDecoderSelector.order(listOf(hw("c2.android.aac.decoder"), sw("c2.other.aac")))
        assertEquals(listOf("c2.other.aac", "c2.android.aac.decoder"), order)
    }

    @Test
    fun `software name heuristic`() {
        assertTrue(AudioDecoderSelector.looksSoftware("OMX.google.aac.decoder"))
        assertTrue(AudioDecoderSelector.looksSoftware("c2.android.aac.decoder"))
        assertTrue(AudioDecoderSelector.looksSoftware("OMX.MTK.AUDIO.DECODER.sw.aac"))
        assertFalse(AudioDecoderSelector.looksSoftware("OMX.qcom.audio.decoder.aac"))
        assertFalse(AudioDecoderSelector.looksSoftware("c2.mtk.aac.decoder"))
    }
}
