package com.cressu.screendock.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvSyncTest {

    private val ms = 1_000_000L

    /** realtime = mono + 1_000 s: a fixed offset between the two local clocks. */
    private fun clock(monoNow: Long, latencyNs: Long = 250 * ms) =
        PlayoutClock(latencyNs, realtimeNs = { monoNow + 1_000_000 * ms }, monoNs = { monoNow })

    @Test fun presentationTimeAddsLatencyOnTheMonotonicClock() {
        val c = clock(monoNow = 5_000 * ms)
        val ptsLocal = 5_000 * ms + 1_000_000 * ms // "now" on the realtime clock
        assertEquals(5_250 * ms, c.presentAtNs(ptsLocal))
        assertEquals(250 * ms, c.delayNs(ptsLocal))
    }

    @Test fun unknownTimestampHasNoPresentationTime() {
        val c = clock(monoNow = 5_000 * ms)
        assertNull(c.presentAtNs(0L))
        assertNull(c.delayNs(-1L))
    }

    @Test fun videoIsHeldOnlyWhileAudioIsPlaying() {
        assertEquals(120L, AvSyncPolicy.videoHoldMs(delayNs = 120 * ms, audioActive = true))
        assertEquals(0L, AvSyncPolicy.videoHoldMs(delayNs = 120 * ms, audioActive = false))
    }

    @Test fun videoIsNotHeldWhenLateUnknownOrClockJumped() {
        assertEquals(0L, AvSyncPolicy.videoHoldMs(delayNs = -30 * ms, audioActive = true))
        assertEquals(0L, AvSyncPolicy.videoHoldMs(delayNs = null, audioActive = true))
        assertEquals(0L, AvSyncPolicy.videoHoldMs(delayNs = 5_000 * ms, audioActive = true))
    }

    @Test fun audioWaitsForItsSlotAccountingForQueuedAudio() {
        // Plays in 300 ms; 100 ms already queued in the AudioTrack -> write in 200 ms.
        assertEquals(AudioAction.Wait(200L), AvSyncPolicy.audioAction(delayNs = 300 * ms, queuedNs = 100 * ms))
    }

    @Test fun audioPlaysImmediatelyWhenRoughlyOnTime() {
        assertEquals(AudioAction.Play, AvSyncPolicy.audioAction(delayNs = 101 * ms, queuedNs = 100 * ms))
        assertEquals(AudioAction.Play, AvSyncPolicy.audioAction(delayNs = 60 * ms, queuedNs = 100 * ms))
    }

    @Test fun audioThatIsFarTooLateIsDropped() {
        assertEquals(AudioAction.Drop, AvSyncPolicy.audioAction(delayNs = -50 * ms, queuedNs = 100 * ms))
    }

    @Test fun audioWithUnknownOrJumpedClockJustPlays() {
        assertEquals(AudioAction.Play, AvSyncPolicy.audioAction(delayNs = null, queuedNs = 0L))
        assertEquals(AudioAction.Play, AvSyncPolicy.audioAction(delayNs = 10_000 * ms, queuedNs = 0L))
    }

    @Test fun audioWaitIsCapped() {
        assertEquals(AudioAction.Wait(AvSyncPolicy.MAX_AUDIO_WAIT_MS), AvSyncPolicy.audioAction(delayNs = 900 * ms, queuedNs = 0L))
    }
}
