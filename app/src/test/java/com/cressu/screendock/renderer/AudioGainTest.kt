package com.cressu.screendock.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioGainTest {

    @Test
    fun `dB mapping clamps and converts`() {
        assertEquals(1.0f, AudioGain.dbToLinear(0f), 0f)
        assertEquals(1.0f, AudioGain.dbToLinear(6f), 0f)
        assertEquals(0.0f, AudioGain.dbToLinear(-144f), 0f)
        assertEquals(0.0f, AudioGain.dbToLinear(-200f), 0f)
        assertEquals(0.5012f, AudioGain.dbToLinear(-6f), 0.001f)
    }

    @Test
    fun `sync on follows the sender`() {
        assertEquals(0.1f, AudioGain.effectiveGain(-20f, followSender = true), 0.0001f)
        assertEquals(0.0f, AudioGain.effectiveGain(-144f, followSender = true), 0f)
    }

    @Test
    fun `sync off ignores the sender and plays at full gain`() {
        assertEquals(1.0f, AudioGain.effectiveGain(-20f, followSender = false), 0f)
        assertEquals(1.0f, AudioGain.effectiveGain(-144f, followSender = false), 0f)
    }
}
