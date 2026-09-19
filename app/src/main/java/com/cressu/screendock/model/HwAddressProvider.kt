package com.cressu.screendock.model

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Derives a stable 6-byte hardware address for AirPlay.
 *
 * Android ≥ 6 hides the real WiFi MAC (returns 02:00:00:00:00:00). AirPlay
 * needs *a* stable 6-byte identifier for the RAOP service name ("<hexmac>@...")
 * and the `_airplay._tcp` `deviceid` TXT record. We derive one deterministically
 * from `ANDROID_ID`, which is per-device+per-app-install stable.
 */
object HwAddressProvider {

    fun getHardwareAddress(context: Context): ByteArray {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "airplay-fallback"
        val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        // RFC-7217-style: take 6 bytes, set the locally-administered + unicast bits.
        val mac = digest.copyOf(6)
        mac[0] = (mac[0].toInt() and 0xFE or 0x02).toByte() // L/A bit set, unicast
        return mac
    }

    /** "XX:XX:..." colon format for the deviceid TXT + logging. */
    fun toColonString(mac: ByteArray): String =
        mac.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** Lowercase hex no separators, for the RAOP service-name prefix. */
    fun toHexNoSep(mac: ByteArray): String =
        mac.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
