package com.cressu.screendock.logs

/**
 * Bounded, thread-safe ring of the most recent log lines. Oldest lines are evicted once
 * [capacity] is reached. Readers get immutable snapshots; nothing hands out the live store.
 */
class LogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {
    init { require(capacity > 0) { "capacity must be positive" } }

    private val lock = Any()
    private val lines = ArrayDeque<LogLine>(capacity)
    private var total = 0L

    /** Appends a line (redacting secrets). Blank/banner lines are ignored. */
    fun append(rawLine: String) {
        val parsed = LogLine.parse(LogRedactor.redact(rawLine)) ?: return
        synchronized(lock) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(parsed)
            total++
        }
    }

    /** Immutable copy, oldest first, keeping only lines at or above [minLevel]. */
    fun snapshot(minLevel: LogLevel = LogLevel.VERBOSE): List<LogLine> = synchronized(lock) {
        lines.filter { it.level >= minLevel }
    }

    /** Monotonic count of lines ever appended; lets the UI cheaply detect changes. */
    fun totalAppended(): Long = synchronized(lock) { total }

    fun clear() = synchronized(lock) { lines.clear() }

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }
}
