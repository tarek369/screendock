package com.cressu.screendock.ui

import com.cressu.screendock.service.MediaMode
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PipLogicTest {
    @Test fun aspect16by9() = assertEquals(1777, PipLogic.aspectMilli(1920, 1080))
    @Test fun unknownSizeFallsBackTo16by9() = assertEquals(1778, PipLogic.aspectMilli(0, 0))
    @Test fun ultraWideClamped() = assertEquals(2390, PipLogic.aspectMilli(5000, 1000))
    @Test fun ultraTallClamped() = assertEquals(418, PipLogic.aspectMilli(1000, 5000))
    @Test fun portraitWithinRange() = assertEquals(562, PipLogic.aspectMilli(1080, 1920))

    @Test fun entersForMirrorAndVideo() {
        assertTrue(PipLogic.shouldEnter(MediaMode.MIRROR, true, false, true))
        assertTrue(PipLogic.shouldEnter(MediaMode.VIDEO, true, false, true))
    }
    @Test fun neverForAudioOrIdle() {
        assertFalse(PipLogic.shouldEnter(MediaMode.AUDIO, true, false, true))
        assertFalse(PipLogic.shouldEnter(MediaMode.NONE, true, false, true))
    }
    @Test fun neverOnTvUnsupportedOrOtherScreen() {
        assertFalse(PipLogic.shouldEnter(MediaMode.MIRROR, true, true, true))
        assertFalse(PipLogic.shouldEnter(MediaMode.MIRROR, true, false, false))
        assertFalse(PipLogic.shouldEnter(MediaMode.MIRROR, false, false, true))
    }
}
