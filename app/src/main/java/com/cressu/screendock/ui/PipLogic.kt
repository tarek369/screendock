package com.cressu.screendock.ui

import com.cressu.screendock.service.MediaMode

/**
 * Pure Picture-in-Picture decisions (no Android types, unit-testable).
 * Approach adapted from jqssun/android-airplay-server (GPL-3.0).
 */
object PipLogic {
    /** Android rejects PiP aspect ratios outside 1:2.39 .. 2.39:1. */
    const val MAX_ASPECT_MILLI = 2390
    const val MIN_ASPECT_MILLI = 418 // 1 / 2.39
    private const val DEFAULT_ASPECT_MILLI = 1778 // 16:9 when the size is unknown
    private const val DEN = 1000

    /** Numerator (denominator is always 1000) of the clamped aspect ratio. */
    fun aspectMilli(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return DEFAULT_ASPECT_MILLI
        val milli = (width.toLong() * DEN / height).toInt()
        return milli.coerceIn(MIN_ASPECT_MILLI, MAX_ASPECT_MILLI)
    }

    const val ASPECT_DENOMINATOR = DEN

    /** Picture-in-picture only makes sense while video is actually on screen, never on TV. */
    fun shouldEnter(
        mode: MediaMode,
        mediaScreenShowing: Boolean,
        isTelevision: Boolean,
        pipSupported: Boolean,
    ): Boolean = pipSupported && !isTelevision && mediaScreenShowing &&
        (mode == MediaMode.MIRROR || mode == MediaMode.VIDEO)
}
