package com.cressu.screendock.service

/**
 * Persisted free-tier usage state. Immutable; [SessionLimiter] returns new copies.
 *
 * - [usedMs]: counted mirroring/video time in the current usage window.
 * - [lastUsageEndWallMs]: wall-clock time counted usage last stopped (idle-reset anchor).
 * - cooldown*: when the budget ran out, the receiver refuses new connections for
 *   [cooldownDurationMs]. Both wall and elapsed-realtime anchors are stored so a user
 *   moving the system clock forward cannot skip the cooldown within one boot.
 */
data class LimiterState(
    val usedMs: Long = 0L,
    val lastUsageEndWallMs: Long = 0L,
    val cooldownStartWallMs: Long = 0L,
    val cooldownStartElapsedMs: Long = 0L,
    val cooldownDurationMs: Long = 0L,
)

/**
 * Pure free-tier policy: [budgetMs] of mirroring/video per usage window; when it is used up,
 * new connections are refused for [cooldownMs]. Disconnecting and reconnecting does NOT
 * refill the budget: it only refills after the cooldown ends or after [idleResetMs] without
 * counted usage. Audio-only streaming is not counted (decision: music is free).
 *
 * Known limit: advancing the wall clock by >= [idleResetMs] while idle refills the budget
 * (cooldown, by contrast, is also anchored to elapsedRealtime and survives clock changes
 * within a boot).
 */
class SessionLimiter(
    val budgetMs: Long = DEFAULT_BUDGET_MS,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    val idleResetMs: Long = DEFAULT_COOLDOWN_MS,
) {

    /** Milliseconds of cooldown left (0 = not cooling down). */
    fun cooldownRemainingMs(s: LimiterState, wallNowMs: Long, elapsedNowMs: Long): Long {
        val dur = s.cooldownDurationMs
        if (dur <= 0L) return 0L
        val wallRemaining = s.cooldownStartWallMs + dur - wallNowMs
        val elapsedSince = elapsedNowMs - s.cooldownStartElapsedMs
        // elapsedSince < 0 means a reboot happened (elapsedRealtime restarted): wall only.
        val remaining = if (elapsedSince >= 0L) maxOf(wallRemaining, dur - elapsedSince) else wallRemaining
        return remaining.coerceIn(0L, dur)
    }

    /** Wall-clock timestamp the cooldown ends at, or 0 when not cooling down. */
    fun cooldownUntilWallMs(s: LimiterState, wallNowMs: Long, elapsedNowMs: Long): Long {
        val rem = cooldownRemainingMs(s, wallNowMs, elapsedNowMs)
        return if (rem > 0L) wallNowMs + rem else 0L
    }

    /** Applies cooldown expiry and the idle reset. */
    fun normalized(s: LimiterState, wallNowMs: Long, elapsedNowMs: Long): LimiterState {
        if (s.cooldownDurationMs > 0L && cooldownRemainingMs(s, wallNowMs, elapsedNowMs) == 0L) {
            return LimiterState()
        }
        val idle = wallNowMs - s.lastUsageEndWallMs
        if (s.cooldownDurationMs == 0L && s.usedMs > 0L && idle >= idleResetMs) {
            return LimiterState()
        }
        return s
    }

    fun budgetRemainingMs(s: LimiterState, wallNowMs: Long, elapsedNowMs: Long): Long {
        val n = normalized(s, wallNowMs, elapsedNowMs)
        if (cooldownRemainingMs(n, wallNowMs, elapsedNowMs) > 0L) return 0L
        return (budgetMs - n.usedMs).coerceAtLeast(0L)
    }

    /** Whether a new free-tier connection may be admitted right now. */
    fun canAdmit(s: LimiterState, wallNowMs: Long, elapsedNowMs: Long): Boolean =
        budgetRemainingMs(s, wallNowMs, elapsedNowMs) > 0L

    /**
     * Record [deltaMs] of counted usage ending at [wallNowMs]. When the budget is exhausted the
     * returned state is in cooldown.
     */
    fun accrue(s: LimiterState, deltaMs: Long, wallNowMs: Long, elapsedNowMs: Long): LimiterState {
        val n = normalized(s, wallNowMs, elapsedNowMs)
        val used = n.usedMs + deltaMs.coerceAtLeast(0L)
        val updated = n.copy(usedMs = used, lastUsageEndWallMs = wallNowMs)
        return if (used >= budgetMs && updated.cooldownDurationMs == 0L) {
            updated.copy(
                cooldownStartWallMs = wallNowMs,
                cooldownStartElapsedMs = elapsedNowMs,
                cooldownDurationMs = cooldownMs,
            )
        } else updated
    }

    /**
     * Combine an in-memory and a persisted state without ever losing enforcement: the later
     * cooldown wins, otherwise the one with more recorded usage.
     */
    fun merge(a: LimiterState, b: LimiterState): LimiterState {
        val aCd = a.cooldownDurationMs > 0L
        val bCd = b.cooldownDurationMs > 0L
        return when {
            aCd && bCd ->
                if (a.cooldownStartWallMs + a.cooldownDurationMs >= b.cooldownStartWallMs + b.cooldownDurationMs) a else b
            aCd -> a
            bCd -> b
            a.usedMs != b.usedMs -> if (a.usedMs > b.usedMs) a else b
            else -> if (a.lastUsageEndWallMs >= b.lastUsageEndWallMs) a else b
        }
    }

    companion object {
        const val DEFAULT_BUDGET_MS = 10L * 60_000L
        const val DEFAULT_COOLDOWN_MS = 30L * 60_000L
    }
}
