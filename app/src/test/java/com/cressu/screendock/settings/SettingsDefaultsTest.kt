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
