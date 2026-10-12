package com.cressu.screendock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLimiterTest {
    private val min = 60_000L
    private val lim = SessionLimiter(budgetMs = 10 * min, cooldownMs = 30 * min, idleResetMs = 30 * min)
    private val t0 = 1_000_000_000L

    @Test fun `fresh state admits with full budget`() {
        val s = LimiterState()
        assertTrue(lim.canAdmit(s, t0, 5_000))
        assertEquals(10 * min, lim.budgetRemainingMs(s, t0, 5_000))
    }

    @Test fun `usage accumulates across reconnects without refilling`() {
        var s = lim.accrue(LimiterState(), 4 * min, t0, 100)
        s = lim.accrue(s, 3 * min, t0 + 5 * min, 100 + 5 * min)
        assertEquals(3 * min, lim.budgetRemainingMs(s, t0 + 6 * min, 100 + 6 * min))
        assertTrue(lim.canAdmit(s, t0 + 6 * min, 100 + 6 * min))
    }

    @Test fun `exhausting budget starts cooldown and refuses`() {
        val s = lim.accrue(LimiterState(), 10 * min, t0, 100)
        assertFalse(lim.canAdmit(s, t0, 100))
        assertEquals(30 * min, lim.cooldownRemainingMs(s, t0, 100))
        assertEquals(t0 + 30 * min, lim.cooldownUntilWallMs(s, t0, 100))
    }

    @Test fun `cooldown expiry restores a full budget`() {
        val s = lim.accrue(LimiterState(), 11 * min, t0, 100)
        val later = t0 + 31 * min
        assertTrue(lim.canAdmit(s, later, 100 + 31 * min))
        assertEquals(10 * min, lim.budgetRemainingMs(s, later, 100 + 31 * min))
        assertEquals(0L, lim.cooldownUntilWallMs(s, later, 100 + 31 * min))
    }

    @Test fun `moving wall clock forward does not skip cooldown within a boot`() {
        val s = lim.accrue(LimiterState(), 10 * min, t0, 100)
        // wall jumps +2h but only 1 min really elapsed
        assertFalse(lim.canAdmit(s, t0 + 120 * min, 100 + min))
        assertEquals(29 * min, lim.cooldownRemainingMs(s, t0 + 120 * min, 100 + min))
    }

    @Test fun `moving wall clock backward never extends beyond one cooldown`() {
        val s = lim.accrue(LimiterState(), 10 * min, t0, 100)
        assertEquals(30 * min, lim.cooldownRemainingMs(s, t0 - 500 * min, 100 + 2 * min).coerceAtMost(30 * min))
    }

    @Test fun `reboot uses wall clock only`() {
        val s = lim.accrue(LimiterState(), 10 * min, t0, 50_000_000)
        // elapsedRealtime restarted from a smaller value; 10 real minutes passed on the wall clock
        assertEquals(20 * min, lim.cooldownRemainingMs(s, t0 + 10 * min, 1_000))
    }

    @Test fun `idle longer than reset window refills budget`() {
        val s = lim.accrue(LimiterState(), 6 * min, t0, 100)
        val later = t0 + 31 * min
        assertEquals(10 * min, lim.budgetRemainingMs(s, later, 100 + 31 * min))
    }

    @Test fun `negative delta is ignored`() {
        val s = lim.accrue(LimiterState(), -5 * min, t0, 100)
        assertEquals(0L, s.usedMs)
    }

    @Test fun `merge keeps the cooldown over stale usage`() {
        val cd = lim.accrue(LimiterState(), 10 * min, t0, 100)
        val stale = LimiterState(usedMs = 2 * min, lastUsageEndWallMs = t0 - min)
        assertEquals(cd, lim.merge(stale, cd))
        assertEquals(cd, lim.merge(cd, stale))
    }

    @Test fun `merge prefers later cooldown and higher usage`() {
        val early = lim.accrue(LimiterState(), 10 * min, t0, 100)
        val late = lim.accrue(LimiterState(), 10 * min, t0 + 5 * min, 100 + 5 * min)
        assertEquals(late, lim.merge(early, late))
        val a = LimiterState(usedMs = 3 * min, lastUsageEndWallMs = t0)
        val b = LimiterState(usedMs = 5 * min, lastUsageEndWallMs = t0)
        assertEquals(b, lim.merge(a, b))
        assertEquals(b, lim.merge(b, a))
    }
}
