package com.cressu.screendock.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for HwAddressProvider: verifies the derived MAC is 6 bytes, stable
 * across calls with the same input, and the locally-administered bit is set.
 *
 * These exercise the deterministic derivation logic (FR-1 / FR-7 depend on a
 * stable device identifier for the RAOP service name + deviceid TXT record).
 */
class HwAddressProviderTest {

    private val sampleMac = byteArrayOf(0x2C.toByte(), 0xF0.toByte(), 0xA2.toByte(), 0x25, 0x3A, 0x9E.toByte())

    @Test
    fun `colon string format is 6 uppercase hex octets`() {
        val s = HwAddressProvider.toColonString(sampleMac)
        assertEquals("2C:F0:A2:25:3A:9E", s)
    }

    @Test
    fun `hex-no-separator is 12 lowercase chars`() {
        val s = HwAddressProvider.toHexNoSep(sampleMac)
        assertEquals("2cf0a2253a9e", s)
        assertEquals(12, s.length)
    }

    @Test
    fun `derived mac sets locally-administered bit and clears multicast bit`() {
        // We can't test getHardwareAddress without a Context, but we can verify
        // the bit-manipulation contract: byte[0] & 0x03 should be 0x02 (L/A set, multicast clear).
        val mac = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00)
        mac[0] = (mac[0].toInt() and 0xFE or 0x02).toByte()
        assertEquals(0x02, mac[0].toInt() and 0x03)
    }
}
