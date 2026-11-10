package com.cressu.screendock.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnToPreviousTrackerTest {

    private fun summonedTracker() = ReturnToPreviousTracker().apply {
        onIntent(summon = true) // onCreate / onNewIntent with the summon extra
        onResume()
    }

    @Test fun `summoned UI returns after the session ends`() {
        val t = summonedTracker()
        assertTrue(t.onSessionEnded(settingOn = true))
        assertTrue(t.consumeReturn(connected = false, settingOn = true))
        assertFalse(t.summoned)
    }

    @Test fun `manually opened UI stays`() {
        val t = ReturnToPreviousTracker().apply { onIntent(summon = false); onResume() }
        assertFalse(t.onSessionEnded(settingOn = true))
        assertFalse(t.consumeReturn(connected = false, settingOn = true))
    }

    @Test fun `setting off keeps the UI`() {
        val t = summonedTracker()
        assertFalse(t.onSessionEnded(settingOn = false))
        assertFalse(t.consumeReturn(connected = false, settingOn = false))
    }

    @Test fun `reconnect inside the grace period cancels the return`() {
        val t = summonedTracker()
        t.onSessionEnded(settingOn = true)
        t.onSessionStarted()
        assertFalse(t.consumeReturn(connected = false, settingOn = true))
        assertTrue(t.summoned) // still summoned: the next session end returns
    }

    @Test fun `still connected at the deadline cancels the return`() {
        val t = summonedTracker()
        t.onSessionEnded(settingOn = true)
        assertFalse(t.consumeReturn(connected = true, settingOn = true))
    }

    @Test fun `manual reopen after leaving clears the summon`() {
        val t = summonedTracker()
        t.onStop()
        t.onIntent(summon = false) // launcher tap
        t.onResume()
        assertFalse(t.onSessionEnded(settingOn = true))
    }

    @Test fun `summon after being stopped keeps the summon`() {
        val t = ReturnToPreviousTracker().apply { onIntent(false); onResume(); onStop() }
        t.onIntent(summon = true)
        t.onResume()
        assertTrue(t.onSessionEnded(settingOn = true))
    }
}
