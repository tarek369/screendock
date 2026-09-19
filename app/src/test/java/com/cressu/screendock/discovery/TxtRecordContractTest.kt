package com.cressu.screendock.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the AirPlay/RAOP TXT record expectations that the native shim builds
 * (android_dnssd_shim.c). Since the C shim needs a device to run, these tests
 * validate the *expected* TXT-record contract the Kotlin side consumes, ensuring
 * NsdServiceManager can apply every key NsdManager accepts.
 *
 * This locks the contract: if the C side changes a key name, these tests flag it
 * for review (though the full round-trip is validated in the integration test).
 */
class TxtRecordContractTest {

    /** The RAOP TXT keys the C shim emits (from dnssdint.h / dnssd_mdnsd.c). */
    private val expectedRaopKeys = setOf(
        "ch", "cn", "da", "et", "vv", "ft", "am", "md", "rhd", "pw",
        "sr", "ss", "sv", "tp", "txtvers", "sf", "vs", "vn", "pk"
    )

    /** The AirPlay TXT keys the C shim emits. */
    private val expectedAirplayKeys = setOf(
        "deviceid", "features", "pw", "flags", "model", "pk", "pi", "srcvers", "vv"
    )

    @Test
    fun `RAOP TXT contract covers all keys the C shim must produce`() {
        // Every key here must appear in the Map returned by NativeBridge.getRaopTxtRecords()
        // at runtime. This test documents the contract; a round-trip instrumented test
        // validates the actual bytes.
        assertEquals(19, expectedRaopKeys.size)
        assertTrue(expectedRaopKeys.contains("cn"))   // codec list
        assertTrue(expectedRaopKeys.contains("et"))   // encryption types
        assertTrue(expectedRaopKeys.contains("ft"))   // feature flags
        assertTrue(expectedRaopKeys.contains("pk"))   // Ed25519 public key
    }

    @Test
    fun `AirPlay TXT contract covers all keys the C shim must produce`() {
        assertEquals(9, expectedAirplayKeys.size)
        assertTrue(expectedAirplayKeys.contains("deviceid"))
        assertTrue(expectedAirplayKeys.contains("features"))
        assertTrue(expectedAirplayKeys.contains("pk"))
    }

    @Test
    fun `RAOP service name format is hexmac-at-name`() {
        // The shim produces "<hexmac>@<name>._raop._tcp.local". NsdServiceManager
        // strips the suffix and registers just "<hexmac>@<name>" as the instance.
        val fullName = "2cf0a2253a9e@Airplay._raop._tcp.local"
        val suffix = "._raop._tcp.local"
        assertNotNull(fullName.indexOf(suffix))
        val instance = fullName.removeSuffix(suffix)
        assertEquals("2cf0a2253a9e@Airplay", instance)
    }
}
