package com.cressu.screendock.logs

/** Formats the shareable text report. Pure, so it is unit-testable. */
object LogReport {
    /**
     * [header] are pre-built "Key: value" lines (version, device, settings summary); they must
     * not contain secrets. Log lines are redacted again here as defence in depth.
     */
    fun format(header: List<String>, lines: List<LogLine>, generatedAt: String): String =
        buildString {
            append("ScreenDock log report\n")
            append("Generated: ").append(generatedAt).append('\n')
            header.forEach { append(it).append('\n') }
            append("Lines: ").append(lines.size).append('\n')
            append("----------------------------------------\n")
            lines.forEach { append(LogRedactor.redact(it.raw)).append('\n') }
        }

    /** Settings summary lines for the header. Deliberately omits the fixed PIN value. */
    fun settingsSummary(
        name: String, resolution: String, fps: Int, h265: Boolean,
        pinMode: String, hls: Boolean, askBeforeAllow: Boolean, scaleMode: String,
    ): List<String> = listOf(
        "Receiver name: $name",
        "Settings: $resolution @ $fps fps, H.265 ${onOff(h265)}, HLS ${onOff(hls)}, scale $scaleMode",
        "Security: PIN mode $pinMode, ask-before-allow ${onOff(askBeforeAllow)}",
    )

    private fun onOff(b: Boolean) = if (b) "on" else "off"
}
