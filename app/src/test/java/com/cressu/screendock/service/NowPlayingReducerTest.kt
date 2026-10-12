package com.cressu.screendock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NowPlayingReducerTest {
    @Test fun `first metadata creates entry`() {
        val n = NowPlayingReducer.withMetadata(null, "T", "A", "Al")
        assertEquals("T", n.title); assertEquals(0f, n.durationSec)
    }
    @Test fun `identical metadata keeps instance`() {
        val a = NowPlayingReducer.withMetadata(null, "T", "A", "Al")
        assertSame(a, NowPlayingReducer.withMetadata(a, "T", "A", "Al"))
    }
    @Test fun `new track resets progress`() {
        val a = NowPlayingReducer.withProgress(NowPlayingReducer.withMetadata(null, "T", "A", "Al"), 50f, 200f)
        val b = NowPlayingReducer.withMetadata(a, "T2", "A", "Al")
        assertEquals(0f, b.positionSec); assertEquals(0f, b.durationSec)
    }
    @Test fun `degenerate progress ignored`() {
        val a = NowPlayingReducer.withProgress(null, 10f, 100f)
        val b = NowPlayingReducer.withProgress(a, 0f, 0f)
        assertEquals(10f, b.positionSec); assertEquals(100f, b.durationSec)
    }
    @Test fun `progress without metadata still tracks`() {
        val a = NowPlayingReducer.withProgress(null, 5f, 60f)
        assertEquals(60f, a.durationSec)
    }
}
