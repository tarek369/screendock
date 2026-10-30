package com.cressu.screendock.logs

/**
 * Scrubs secrets before a line enters the buffer, so neither the viewer nor a shared report
 * can ever contain them. Native code logs only "pin=fixed/random/off", but UxPlay and future
 * code paths could print digits, so anything PIN-shaped is masked defensively.
 */
object LogRedactor {
    private val PIN = Regex("""(?i)(\bpin\w*\s*[=:]?\s*"?)\d{4}\b""")
    private val SECRET = Regex("""(?i)(\b(?:password|passwd|token|secret|authorization|psk)\s*[=:]\s*)\S+""")

    fun redact(line: String): String =
        line.replace(PIN, "$1****").replace(SECRET, "$1****")
}
