// Adapted from jqssun/android-airplay-server (GPL-3.0)
package com.cressu.screendock.discovery

import java.io.ByteArrayOutputStream
import java.net.Inet4Address

/**
 * Pure DNS/mDNS wire helpers used by [UsbMdnsAdvertiser]: name encoding with length limits,
 * name parsing with compression-pointer handling, record containers and tether-iface filtering.
 * No Android dependencies, so they are unit-testable on the JVM.
 */

/** One advertised DNS-SD service instance (same data NsdServiceManager registers). */
data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

internal class MdnsRecord(val name: List<String>, val type: Int, val unique: Boolean, val ttl: Int, val rdata: ByteArray) {
    val key get() = Triple(name.joinToString(".").lowercase(), type, rdata.asList())
}

/** Tether-style interfaces only (never wlan0/eth0, which NsdManager already serves). */
internal val USB_IFACE_REGEX = Regex("(usb|rndis|ncm)\\d+")

internal fun isUsbTetherIface(name: String) = USB_IFACE_REGEX.matches(name)

/** CIDR prefix length -> IPv4 netmask as an int. */
internal fun prefixToMask(prefix: Int) = if (prefix <= 0) 0 else -1 shl (32 - prefix.coerceAtMost(32))

internal fun sameSubnet(a: Inet4Address, b: Inet4Address, mask: Int) = ((_ip(a) xor _ip(b)) and mask) == 0

internal fun _ip(a: Inet4Address) = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

internal fun _eq(a: List<String>, b: List<String>) =
    a.size == b.size && a.indices.all { a[it].equals(b[it], ignoreCase = true) }

internal fun _rd16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

internal fun _bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()

internal fun ByteArrayOutputStream._u16(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }

internal fun ByteArrayOutputStream._u32(v: Int) { _u16(v ushr 16); _u16(v and 0xFFFF) }

internal fun ByteArrayOutputStream._label(s: String, max: Int) {
    val b = s.toByteArray()
    var n = minOf(b.size, max)
    if (n < b.size) while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--
    write(n)
    write(b, 0, n)
}

internal fun _name(labels: List<String>) = _bytes {
    labels.forEach { _label(it, 63) }
    write(0)
}

internal fun _readName(d: ByteArray, start: Int): Pair<List<String>, Int>? {
    val labels = mutableListOf<String>()
    var pos = start
    var end = -1
    var jumps = 0
    while (true) {
        if (pos >= d.size) return null
        val len = d[pos].toInt() and 0xFF
        when {
            len == 0 -> return labels to (if (end < 0) pos + 1 else end)
            (len and 0xC0) == 0xC0 -> {
                if (pos + 1 >= d.size || ++jumps > 16) return null
                if (end < 0) end = pos + 2
                pos = ((len and 0x3F) shl 8) or (d[pos + 1].toInt() and 0xFF)
            }
            else -> {
                if (pos + 1 + len > d.size) return null
                labels += String(d, pos + 1, len)
                pos += 1 + len
            }
        }
    }
}
