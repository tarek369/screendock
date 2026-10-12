package com.cressu.screendock.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM sanity tests for AirPlaySettings defaults + SettingsRepository data shape.
 *
 * (The full DataStore round-trip needs an instrumented test with a Context;
 * this file validates the data class defaults the brief FR-7 specifies.)
 */
class SettingsDefaultsTest {

    @Test
    fun `default display name is non-empty`() {
        val s = AirPlaySettings()
        assertTrue(s.displayName.isNotEmpty())
    }

    @Test
    fun `default resolution is Native panel (0x0) at 60fps`() {
        val s = AirPlaySettings()
        assertEquals(0, s.maxWidth)
        assertEquals(0, s.maxHeight)
        assertEquals(60, s.maxFps)
    }

    @Test
    fun `h265 is enabled by default`() {
        val s = AirPlaySettings()
        assertTrue(s.h265Enabled)
    }
}

class Phase2SettingsDefaultsTest {
    @Test fun `phase 2 defaults`() {
        val s = AirPlaySettings()
        assertEquals(PinMode.OFF, s.pinMode)
        assertEquals("", s.fixedPin)
        assertEquals(false, s.askBeforeAllow)
        assertEquals(false, s.autoStartOnBoot)
        assertEquals("FIT", s.scaleMode)
        assertEquals(true, s.hlsEnabled)
        assertEquals(false, s.keepScreenOnAudio)
        assertEquals(true, s.autoOpenOnConnect)
        assertEquals(false, s.onboardingDone)
        assertEquals(0, s.successfulSessions)
        assertEquals(false, s.reviewPrompted)
    }

    @Test fun `default display name includes model`() =
        assertEquals("ScreenDock (Pixel 8)", DisplayNames.defaultFor("Pixel 8"))

    @Test fun `default display name falls back for blank or null model`() {
        assertEquals("ScreenDock", DisplayNames.defaultFor(null))
        assertEquals("ScreenDock", DisplayNames.defaultFor("  "))
    }

    @Test fun `default display name strips separators and truncates`() {
        assertEquals("ScreenDock (SM-G991B)", DisplayNames.defaultFor("SM-G991B@(x)".replace("x", "")))
        val long = DisplayNames.defaultFor("A".repeat(100))
        assertTrue(long.length <= "ScreenDock ()".length + 28)
    }
}
