package com.cressu.screendock.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoKeysTest {

    @Test fun `toggle keys`() {
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            .forEach { assertEquals(VideoKeyAction.TOGGLE, VideoKeys.actionFor(it)) }
    }

    @Test fun `discrete play and pause`() {
        assertEquals(VideoKeyAction.PLAY, VideoKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(VideoKeyAction.PAUSE, VideoKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    @Test fun `seek keys`() {
        assertEquals(VideoKeyAction.SEEK_BACK, VideoKeys.actionFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(VideoKeyAction.SEEK_BACK, VideoKeys.actionFor(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(VideoKeyAction.SEEK_FORWARD, VideoKeys.actionFor(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(VideoKeyAction.SEEK_FORWARD, VideoKeys.actionFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }

    @Test fun `back and unrelated keys are not handled`() {
        assertNull(VideoKeys.actionFor(KeyEvent.KEYCODE_BACK))
        assertNull(VideoKeys.actionFor(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(VideoKeys.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun `only seeks repeat`() {
        assertTrue(VideoKeys.repeats(VideoKeyAction.SEEK_FORWARD))
        assertFalse(VideoKeys.repeats(VideoKeyAction.TOGGLE))
    }

    @Test fun `seek clamps to zero and duration`() {
        assertEquals(0L, VideoKeys.seekTarget(4_000L, 60_000L, VideoKeyAction.SEEK_BACK))
        assertEquals(60_000L, VideoKeys.seekTarget(55_000L, 60_000L, VideoKeyAction.SEEK_FORWARD))
        assertEquals(30_000L, VideoKeys.seekTarget(20_000L, 60_000L, VideoKeyAction.SEEK_FORWARD))
    }

    @Test fun `unknown duration has no upper clamp`() {
        // Media3 C.TIME_UNSET is Long.MIN_VALUE + 1
        assertEquals(70_000L, VideoKeys.seekTarget(60_000L, Long.MIN_VALUE + 1, VideoKeyAction.SEEK_FORWARD))
    }
}
