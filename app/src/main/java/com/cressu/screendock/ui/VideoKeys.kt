package com.cressu.screendock.ui

import android.view.KeyEvent

/**
 * TV remote / keyboard keys for the AirPlay video screen (the on-screen controller is hidden).
 * Pure mapping, unit-tested. Adapted from jqssun/android-airplay-server PR #16 (GPL-3.0).
 *
 * The keys only drive the local player; the sender learns the new state from the regular
 * playback-info push (rate 0 after pause, new position after a seek).
 */
enum class VideoKeyAction { TOGGLE, PLAY, PAUSE, SEEK_BACK, SEEK_FORWARD }

object VideoKeys {
    const val SEEK_STEP_MS = 10_000L

    fun actionFor(keyCode: Int): VideoKeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE -> VideoKeyAction.TOGGLE
        KeyEvent.KEYCODE_MEDIA_PLAY -> VideoKeyAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> VideoKeyAction.PAUSE
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> VideoKeyAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> VideoKeyAction.SEEK_FORWARD
        else -> null
    }

    /** Seeks repeat while the key is held; play/pause toggles act once per press. */
    fun repeats(action: VideoKeyAction): Boolean =
        action == VideoKeyAction.SEEK_BACK || action == VideoKeyAction.SEEK_FORWARD

    /** New position for a seek, clamped to [0, duration] (duration <= 0 = unknown/live: no upper clamp). */
    fun seekTarget(positionMs: Long, durationMs: Long, action: VideoKeyAction): Long {
        val delta = if (action == VideoKeyAction.SEEK_BACK) -SEEK_STEP_MS else SEEK_STEP_MS
        val target = (positionMs + delta).coerceAtLeast(0L)
        return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
    }
}
