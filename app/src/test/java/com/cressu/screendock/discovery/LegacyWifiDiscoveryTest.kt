package com.cressu.screendock.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyWifiDiscoveryTest {

    @Test
    fun `fallback only on Android 7 and older`() {
        assertTrue(isLegacyWifiFallbackNeeded(24))
        assertTrue(isLegacyWifiFallbackNeeded(25))
        assertFalse(isLegacyWifiFallbackNeeded(26))
        assertFalse(isLegacyWifiFallbackNeeded(36))
    }

    @Test
    fun `wifi filter matches wlan only`() {
        assertTrue(isLegacyWifiIface("wlan0"))
        assertTrue(isLegacyWifiIface("wlan1"))
        assertFalse(isLegacyWifiIface("p2p-wlan0-0"))
        assertFalse(isLegacyWifiIface("usb0"))
        assertFalse(isLegacyWifiIface("eth0"))
        assertFalse(isLegacyWifiIface("lo"))
        assertFalse(isLegacyWifiIface("wlan"))
    }

    @Test
    fun `wifi and usb filters never overlap`() {
        listOf("wlan0", "usb0", "ncm0", "rndis0").forEach {
            assertFalse(it, isLegacyWifiIface(it) && isUsbTetherIface(it))
        }
    }

    @Test
    fun `nsd error codes are spelled out`() {
        assertEquals("FAILURE_INTERNAL_ERROR(0)", NsdServiceManager.describeNsdError(0))
        assertEquals("FAILURE_ALREADY_ACTIVE(3)", NsdServiceManager.describeNsdError(3))
        assertEquals("FAILURE_MAX_LIMIT(4)", NsdServiceManager.describeNsdError(4))
        assertEquals("FAILURE_BAD_PARAMETERS(6)", NsdServiceManager.describeNsdError(6))
        assertEquals("UNKNOWN(42)", NsdServiceManager.describeNsdError(42))
    }

    @Test
    fun `txt wire size counts length bytes keys and values`() {
        // "a=bc" -> 1 + 1 + 1 + 2 = 5 ; "flag" (no value) -> 1 + 4 = 5
        assertEquals(10, NsdServiceManager.txtWireSize(mapOf("a" to "bc".toByteArray(), "flag" to null)))
        assertEquals(0, NsdServiceManager.txtWireSize(emptyMap()))
    }
}
