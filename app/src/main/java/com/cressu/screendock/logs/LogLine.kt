package com.cressu.screendock.logs

/** Android log priority, ordered by severity. [letter] is the logcat single-char code. */
enum class LogLevel(val letter: Char) {
    VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E');

    companion object {
        /** 'F'/'A' (fatal/assert) fold into ERROR; unknown letters become null. */
        fun fromLetter(c: Char): LogLevel? = when (c) {
            'V' -> VERBOSE
            'D' -> DEBUG
            'I' -> INFO
            'W' -> WARN
            'E', 'F', 'A' -> ERROR
            else -> null
        }
    }
}

/** One parsed logcat line. [raw] is the already-redacted original text. */
data class LogLine(val level: LogLevel, val tag: String, val raw: String) {
    companion object {
        // `MM-DD HH:MM:SS.mmm  PID  TID L TAG     : message` (logcat -v threadtime)
        private val THREADTIME =
            Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+([VDIWEFA])\s+(.*?)\s*:\s.*$""")

        /** Returns null for blank lines and logcat banners like `--------- beginning of main`. */
        fun parse(rawLine: String): LogLine? {
            if (rawLine.isBlank() || rawLine.startsWith("---------")) return null
            val m = THREADTIME.matchEntire(rawLine)
            val level = m?.groupValues?.get(1)?.firstOrNull()?.let(LogLevel::fromLetter) ?: LogLevel.INFO
            val tag = m?.groupValues?.get(2).orEmpty()
            return LogLine(level, tag, rawLine)
        }
    }
}
