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

/** What we remember about past review requests (Play never tells us if a dialog was shown). */
data class ReviewHistory(
    val askCount: Int = 0,
    val lastAskedMs: Long = 0L,
    /** successfulSessions at the time of the last ask; a new good session is needed before asking again. */
    val sessionsAtLastAsk: Int = 0,
)

object ReviewPolicy {
    const val MIN_SUCCESSFUL_SESSIONS = 1
    /** Play's quota often shows nothing, so retry a few times instead of giving up after one call. */
    const val MAX_ASKS = 3
    const val RETRY_INTERVAL_MS = 14L * 24 * 60 * 60 * 1000

    /**
     * Ask for an in-app review on the idle screen (after a session ends or when the
     * app is opened) once the user has had a good session since the last ask —
     * never while media is active or the paywall/cooldown is showing.
     */
    fun shouldRequest(
        successfulSessions: Int,
        history: ReviewHistory,
        nowMs: Long,
        mediaActive: Boolean,
        paywallOrCooldown: Boolean = false,
    ): Boolean = !paywallOrCooldown &&
        !mediaActive &&
        successfulSessions >= MIN_SUCCESSFUL_SESSIONS &&
        successfulSessions > history.sessionsAtLastAsk &&
        canAskAgain(history, nowMs)

    /** Ask right after the user completes a Pro purchase in the app (still capped by [MAX_ASKS]). */
    fun shouldRequestAfterPurchase(wasPro: Boolean, isPro: Boolean, history: ReviewHistory): Boolean =
        !wasPro && isPro && history.askCount < MAX_ASKS

    private fun canAskAgain(history: ReviewHistory, nowMs: Long): Boolean = when {
        history.askCount >= MAX_ASKS -> false
        history.askCount == 0 -> true
        else -> nowMs - history.lastAskedMs >= RETRY_INTERVAL_MS
    }
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
