package com.cressu.screendock.discovery

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsCodecTest {

    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun encodesNameAsLengthPrefixedLabels() {
        val b = _name(listOf("a", "bc", "local"))
        assertArrayEquals(byteArrayOf(1, 'a'.code.toByte(), 2, 'b'.code.toByte(), 'c'.code.toByte(), 5) +
            "local".toByteArray() + byteArrayOf(0), b)
    }

    @Test
    fun longLabelIsTruncatedTo63Bytes() {
        val b = _name(listOf("x".repeat(100)))
        assertEquals(63, b[0].toInt())
        assertEquals(1 + 63 + 1, b.size)
    }

    @Test
    fun truncationNeverSplitsMultibyteChar() {
        // 62 ASCII + a 2-byte char straddling byte 63 -> cut must back off to 62
        val b = _name(listOf("a".repeat(62) + "é"))
        assertEquals(62, b[0].toInt())
    }

    @Test
    fun readNameRoundTrips() {
        val b = _name(listOf("My Mac", "_airplay", "_tcp", "local"))
        val (labels, end) = _readName(b, 0)!!
        assertEquals(listOf("My Mac", "_airplay", "_tcp", "local"), labels)
        assertEquals(b.size, end)
    }

    @Test
    fun readNameFollowsCompressionPointer() {
        val first = _name(listOf("foo", "local")) // at offset 0
        // second name: label "bar" then pointer to offset 0
        val second = byteArrayOf(3, 'b'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), 0xC0.toByte(), 0)
        val data = first + second
        val (labels, end) = _readName(data, first.size)!!
        assertEquals(listOf("bar", "foo", "local"), labels)
        assertEquals(data.size, end) // position after the pointer, not after the target
    }

    @Test
    fun readNameRejectsPointerLoop() {
        val data = byteArrayOf(0xC0.toByte(), 0) // points at itself
        assertNull(_readName(data, 0))
    }

    @Test
    fun readNameRejectsTruncatedInput() {
        assertNull(_readName(byteArrayOf(5, 'a'.code.toByte()), 0))
        assertNull(_readName(byteArrayOf(), 0))
    }

    @Test
    fun u16AndU32AreBigEndian() {
        val b = _bytes { _u16(0x1234); _u32(0x01020304) }
        assertArrayEquals(byteArrayOf(0x12, 0x34, 1, 2, 3, 4), b)
        assertEquals(0x1234, _rd16(b, 0))
    }

    @Test
    fun nameComparisonIsCaseInsensitive() {
        assertTrue(_eq(listOf("Foo", "local"), listOf("foo", "LOCAL")))
        assertFalse(_eq(listOf("foo"), listOf("foo", "local")))
    }

    @Test
    fun ifaceFilterAcceptsOnlyTetherInterfaces() {
        listOf("usb0", "rndis0", "ncm0", "usb12").forEach { assertTrue(it, isUsbTetherIface(it)) }
        listOf("wlan0", "eth0", "lo", "rmnet_data0", "usb", "ap0", "p2p0", "myusb0").forEach {
            assertFalse(it, isUsbTetherIface(it))
        }
    }

    @Test
    fun prefixMaskAndSubnetMatching() {
        assertEquals(0, prefixToMask(0))
        assertEquals(-256, prefixToMask(24))
        assertEquals(-1, prefixToMask(32))
        val mask = prefixToMask(24)
        assertTrue(sameSubnet(v4("10.41.52.1"), v4("10.41.52.200"), mask))
        assertFalse(sameSubnet(v4("10.41.52.1"), v4("192.168.20.5"), mask))
    }

    @Test
    fun servicesForReusesNativeTxtAndStripsRaopSuffix() {
        val airplay = mapOf("deviceid" to "AA:BB")
        val raop = mapOf("cn" to "0,1")
        val s = UsbMdnsAdvertiser.servicesFor("ScreenDock", 7000, airplay, raop, "AABB@ScreenDock._raop._tcp.local.")
        assertEquals(listOf("_airplay._tcp", "_raop._tcp"), s.map { it.type })
        assertEquals("ScreenDock", s[0].name)
        assertEquals("AABB@ScreenDock", s[1].name)
        assertEquals(airplay, s[0].txt)
        assertEquals(raop, s[1].txt)
        assertTrue(s.all { it.port == 7000 })
    }

    @Test
    fun responderGatedToSdk36AndBelow() {
        assertTrue(UsbMdnsAdvertiser.isNeeded(34))
        assertTrue(UsbMdnsAdvertiser.isNeeded(36))
        assertFalse(UsbMdnsAdvertiser.isNeeded(37))
    }
}
