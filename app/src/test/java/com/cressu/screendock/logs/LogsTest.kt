package com.cressu.screendock.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsTest {
    private fun line(level: Char, tag: String, msg: String) =
        "10-30 12:00:01.123  4242  4243 $level $tag: $msg"

    @Test fun parsesThreadtimeLine() {
        val l = LogLine.parse(line('W', "AirplayNative", "hello world"))!!
        assertEquals(LogLevel.WARN, l.level)
        assertEquals("AirplayNative", l.tag)
    }

    @Test fun fatalFoldsToErrorAndBannersAreDropped() {
        assertEquals(LogLevel.ERROR, LogLine.parse(line('F', "X", "boom"))!!.level)
        assertNull(LogLine.parse("--------- beginning of main"))
        assertNull(LogLine.parse("   "))
    }

    @Test fun unparseableLineDefaultsToInfo() {
        assertEquals(LogLevel.INFO, LogLine.parse("garbage")!!.level)
    }

    @Test fun ringEvictsOldestAtCapacity() {
        val b = LogBuffer(3)
        (1..5).forEach { b.append(line('I', "T", "m$it")) }
        val snap = b.snapshot()
        assertEquals(3, snap.size)
        assertTrue(snap.first().raw.endsWith("m3"))
        assertTrue(snap.last().raw.endsWith("m5"))
        assertEquals(5L, b.totalAppended())
    }

    @Test fun snapshotFiltersByLevelAndIsImmutableCopy() {
        val b = LogBuffer(10)
        b.append(line('D', "T", "d"))
        b.append(line('E', "T", "e"))
        val errors = b.snapshot(LogLevel.WARN)
        assertEquals(1, errors.size)
        b.append(line('E', "T", "e2"))
        assertEquals(1, errors.size)
    }

    @Test fun clearEmptiesBuffer() {
        val b = LogBuffer(4)
        b.append(line('I', "T", "x"))
        b.clear()
        assertTrue(b.snapshot().isEmpty())
    }

    @Test fun redactsPinsAndSecrets() {
        val b = LogBuffer(4)
        b.append(line('I', "T", "pin=1234 and PIN: 9876 password=hunter2"))
        val raw = b.snapshot().single().raw
        assertFalse(raw.contains("1234"))
        assertFalse(raw.contains("9876"))
        assertFalse(raw.contains("hunter2"))
        assertTrue(raw.contains("pin=****"))
    }

    @Test fun keepsNonSecretWords() {
        assertEquals("pin=fixed hls=1", LogRedactor.redact("pin=fixed hls=1"))
        assertEquals("pinned 12345", LogRedactor.redact("pinned 12345"))
    }

    @Test fun reportContainsHeaderLinesAndCount() {
        val lines = listOf(LogLine.parse(line('I', "T", "a"))!!, LogLine.parse(line('E', "T", "b"))!!)
        val header = LogReport.settingsSummary("Den TV", "native", 60, true, "FIXED", true, false, "FIT")
        val text = LogReport.format(listOf("App: ScreenDock 0.3.6") + header, lines, "2026-10-30")
        assertTrue(text.startsWith("ScreenDock log report\nGenerated: 2026-10-30\n"))
        assertTrue(text.contains("App: ScreenDock 0.3.6"))
        assertTrue(text.contains("PIN mode FIXED"))
        assertTrue(text.contains("Lines: 2"))
        assertTrue(text.trimEnd().endsWith(": b"))
    }

    @Test fun reportRedactsEvenPreRedactedBypass() {
        val raw = LogLine(LogLevel.INFO, "T", "10-30 12:00:01.123 1 1 I T: pin=5555")
        assertFalse(LogReport.format(emptyList(), listOf(raw), "now").contains("5555"))
    }
}
