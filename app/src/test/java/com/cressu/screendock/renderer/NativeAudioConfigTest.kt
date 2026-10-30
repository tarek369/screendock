package com.cressu.screendock.renderer

import com.cressu.screendock.settings.AirPlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeAudioConfigTest {

    @Test
    fun `low latency audio is off by default with auto cushion`() {
        val s = AirPlaySettings()
        assertFalse(s.lowLatencyAudio)
        assertEquals(NativeAudioConfig.CUSHION_AUTO, s.audioCushionMs)
        assertFalse(NativeAudioConfig().enabled)
    }

    @Test
    fun `cushion is clamped to supported range`() {
        assertEquals(0, NativeAudioConfig.sanitizeCushion(-50))
        assertEquals(80, NativeAudioConfig.sanitizeCushion(80))
        assertEquals(NativeAudioConfig.MAX_CUSHION_MS, NativeAudioConfig.sanitizeCushion(99_999))
        assertEquals(NativeAudioConfig.MAX_CUSHION_MS, NativeAudioConfig(true, 99_999).effectiveCushionMs)
    }

    @Test
    fun `stored value snaps to nearest offered choice`() {
        assertEquals(0, NativeAudioConfig.nearestChoice(0))
        assertEquals(40, NativeAudioConfig.nearestChoice(50))
        assertEquals(150, NativeAudioConfig.nearestChoice(160))
        assertEquals(300, NativeAudioConfig.nearestChoice(10_000))
    }

    @Test
    fun `sink is AudioTrack unless enabled and engine opened`() {
        val on = NativeAudioConfig(enabled = true)
        val off = NativeAudioConfig(enabled = false)
        assertEquals(AudioSink.OBOE, AudioSinkSelector.select(on, 1234L))
        assertEquals(AudioSink.AUDIO_TRACK, AudioSinkSelector.select(on, 0L)) // engine failed -> fallback
        assertEquals(AudioSink.AUDIO_TRACK, AudioSinkSelector.select(off, 1234L)) // setting off wins
    }
}
