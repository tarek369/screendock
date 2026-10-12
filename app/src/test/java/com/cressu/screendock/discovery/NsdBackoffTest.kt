package com.cressu.screendock.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class NsdBackoffTest {
    @Test fun `backoff doubles then caps at 30s`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map { NsdServiceManager.backoffMs(it) })
    }
    @Test fun `negative attempt is safe`() = assertEquals(1_000L, NsdServiceManager.backoffMs(-3))
}
