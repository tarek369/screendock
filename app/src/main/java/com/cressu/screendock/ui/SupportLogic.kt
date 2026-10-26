package com.cressu.screendock.ui

import java.net.URLEncoder

/** Pure, Android-free logic for the "nothing connected" help card and the support email (unit-tested). */

object ConnectHelpPolicy {
    /** How long the receiver sits ready with no sender before the help card replaces the guide. */
    const val DELAY_MS = 120_000L

    /**
     * Only first-timers get the card: anyone with a successful session already
     * knows how to connect, and the card would nag them every time they idle.
     */
    fun shouldShow(ready: Boolean, waitedMs: Long, successfulSessions: Int, dismissed: Boolean): Boolean =
        ready && !dismissed && successfulSessions == 0 && waitedMs >= DELAY_MS
}

object WifiBand {
    /** "2.4" / "5" / "6" GHz for a channel frequency in MHz; null when unknown. */
    fun fromMhz(mhz: Int): String? = when (mhz) {
        in 2400..2500 -> "2.4"
        in 4900..5899 -> "5"
        in 5925..7125 -> "6"
        else -> null
    }
}

enum class SupportProblem(val summary: String) {
    NOT_FOUND("device doesn't appear"),
    DROPS("connects, then drops"),
    BLACK_SCREEN("black screen"),
    NO_SOUND("no sound"),
    OTHER("other problem"),
}

enum class SenderKind(val label: String) {
    IPHONE("iPhone"),
    IPAD("iPad"),
    MAC("Mac"),
}

/** Everything the support email reports about this install. No IPs, names or identifiers. */
data class SupportDiagnostics(
    val appVersion: String,
    val versionCode: Long,
    val pro: Boolean,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val network: String,
    val vpnOn: Boolean,
    val receiverRunning: Boolean,
    val resolution: String,
    val fps: Int,
    val successfulSessions: Int,
)

object SupportEmail {
    const val ADDRESS = "tarek@cressu.com"

    fun subject(problem: SupportProblem, sender: SenderKind): String =
        "ScreenDock: ${problem.summary} (${sender.label})"

    /**
     * [prompt] is the localized "tell us what happened" line; the diagnostics
     * block stays English so every report reads the same on our side.
     */
    fun body(prompt: String, problem: SupportProblem, sender: SenderKind, d: SupportDiagnostics): String {
        val receiver = if (d.receiverRunning) "running, ${d.resolution} @ ${d.fps} fps" else "stopped"
        return listOf(
            prompt,
            "",
            "",
            "--- Please keep the details below ---",
            "Problem: ${problem.summary}",
            "Sender: ${sender.label}",
            "App: ScreenDock ${d.appVersion} (${d.versionCode}), ${if (d.pro) "Pro" else "Free"}",
            "Device: ${d.manufacturer} ${d.model}",
            "Android: ${d.androidRelease} (API ${d.sdkInt})",
            "Network: ${d.network}, VPN ${if (d.vpnOn) "on" else "off"}",
            "Receiver: $receiver",
            "Successful sessions: ${d.successfulSessions}",
        ).joinToString("\n")
    }

    /** RFC 6068 mailto: spaces as %20 (not '+', which mail apps show literally). */
    fun mailtoUri(subject: String, body: String): String =
        "mailto:$ADDRESS?subject=${encode(subject)}&body=${encode(body)}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
