package com.cressu.screendock.ui

import com.cressu.screendock.renderer.QuadExtents
import com.cressu.screendock.renderer.ScaleMode
import com.cressu.screendock.renderer.VideoGeometry
import java.util.Locale

/** Pure, Android-free UI helpers (unit-tested). */

object CountdownFormatter {
    /** "m:ss" for a remaining time in ms; never negative, rounds up to the next second. */
    fun format(remainingMs: Long): String {
        val totalSec = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }
}

object ClockFormatter {
    /** Playback clock: "m:ss", or "h:mm:ss" past an hour. Negative / NaN → "0:00". */
    fun format(seconds: Float): String {
        val total = if (seconds.isNaN() || seconds < 0f) 0L else seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Progress fraction in [0,1]; 0 when the duration is unknown. */
    fun fraction(positionSec: Float, durationSec: Float): Float =
        if (durationSec <= 0f || durationSec.isNaN() || positionSec.isNaN()) 0f
        else (positionSec / durationSec).coerceIn(0f, 1f)
}

object ReviewPolicy {
    const val MIN_SUCCESSFUL_SESSIONS = 2

    /**
     * Ask for an in-app review only after enough successful sessions, only once
     * ever, and only on the idle screen right after a session ended — never
     * while media is active.
     */
    fun shouldRequest(
        successfulSessions: Int,
        reviewPrompted: Boolean,
        sessionJustEnded: Boolean,
        mediaActive: Boolean,
        paywallOrCooldown: Boolean = false,
    ): Boolean = !reviewPrompted &&
        !paywallOrCooldown &&
        !mediaActive &&
        sessionJustEnded &&
        successfulSessions >= MIN_SUCCESSFUL_SESSIONS

    /** Ask once right after the user completes a Pro purchase in the app. */
    fun shouldRequestAfterPurchase(wasPro: Boolean, isPro: Boolean, reviewPrompted: Boolean): Boolean =
        !reviewPrompted && !wasPro && isPro
}

object PinValidator {
    const val PIN_LENGTH = 4

    /** Keep only digits, at most [PIN_LENGTH]. */
    fun sanitize(input: String): String = input.filter { it in '0'..'9' }.take(PIN_LENGTH)

    fun isValidFixedPin(pin: String): Boolean =
        pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
}

object ScaleModes {
    fun next(mode: ScaleMode): ScaleMode = when (mode) {
        ScaleMode.FIT -> ScaleMode.FILL
        ScaleMode.FILL -> ScaleMode.STRETCH
        ScaleMode.STRETCH -> ScaleMode.FIT
    }

    /** Parse a persisted name; unknown/blank → FIT. */
    fun parse(name: String?): ScaleMode =
        ScaleMode.entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: ScaleMode.FIT
}

object TouchMapper {
    /**
     * Map a view-space touch to normalized [0..1] coordinates on the sender's
     * screen, honouring the active [mode] (letterbox bars in FIT yield null;
     * FILL maps into the visible, cropped part).
     */
    fun normalize(
        x: Float, y: Float,
        viewW: Int, viewH: Int,
        mode: ScaleMode, videoAspect: Float,
    ): Pair<Double, Double>? {
        if (viewW <= 0 || viewH <= 0) return null
        val q: QuadExtents = VideoGeometry.quadExtents(mode, videoAspect, viewW, viewH)
        val nx = (x / viewW - (0.5f - q.halfW / 2f)) / q.halfW
        val ny = (y / viewH - (0.5f - q.halfH / 2f)) / q.halfH
        if (nx !in 0f..1f || ny !in 0f..1f) return null
        return nx.toDouble() to ny.toDouble()
    }
}
