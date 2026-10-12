package com.cressu.screendock.service

import android.os.SystemClock
import android.util.Log
import com.cressu.screendock.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Glue between the pure [SessionLimiter] policy and the service: keeps the persisted
 * [LimiterState], counts mirroring/video time while [setCounting] is true, persists it, and
 * notifies when the budget is exhausted so the service can end the session.
 *
 * Thread-safe: [canAdmit]/[setCounting] are called from native and main threads.
 */
class LimiterCoordinator(
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
    private val limiter: SessionLimiter = SessionLimiter(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    /** Wall-clock ms the cooldown ends at (0 = none). */
    private val onCooldownChanged: (Long) -> Unit,
    /** Budget used up: end the active session now. */
    private val onExhausted: () -> Unit,
) {
    private val lock = Any()
    private var state = LimiterState()
    private var lastAccrueElapsed = 0L
    private var job: Job? = null
    private var expiryJob: Job? = null
    private val saveMutex = Mutex()

    suspend fun load() {
        val loaded = repo.loadLimiterState()
        synchronized(lock) { state = limiter.merge(state, loaded) } // never let stale disk state win
        publishCooldown()
    }

    /** True when a new free-tier connection may be admitted. */
    fun canAdmit(): Boolean = synchronized(lock) {
        limiter.canAdmit(state, wallClock(), elapsedClock())
    }.also { publishCooldown() }

    /** Start/stop counting mirroring/video time. Idempotent. */
    fun setCounting(count: Boolean) {
        synchronized(lock) {
            if (count && job == null) {
                lastAccrueElapsed = elapsedClock()
                job = scope.launch { countLoop() }
            } else if (!count && job != null) {
                accrueLocked()
                job?.cancel(); job = null
                persist()
            }
        }
        if (!count) publishCooldown()
    }

    /** Pro user: drop any enforcement signal (state is kept for if the entitlement lapses). */
    fun publishCooldown() {
        val until = synchronized(lock) { limiter.cooldownUntilWallMs(state, wallClock(), elapsedClock()) }
        onCooldownChanged(until)
        expiryJob?.cancel()
        if (until > 0L) {
            expiryJob = scope.launch {
                delay((until - wallClock()).coerceAtLeast(500L) + 250L)
                publishCooldown()
            }
        }
    }

    private suspend fun countLoop() {
        while (scope.isActive) {
            val remaining = synchronized(lock) { limiter.budgetRemainingMs(state, wallClock(), elapsedClock()) }
            if (remaining <= 0L) { exhaust(); return }
            delay(remaining.coerceIn(250L, TICK_MS))
            val exhausted = synchronized(lock) {
                accrueLocked()
                limiter.budgetRemainingMs(state, wallClock(), elapsedClock()) <= 0L
            }
            persist()
            if (exhausted) { exhaust(); return }
        }
    }

    private suspend fun exhaust() {
        Log.i(TAG, "free-tier budget exhausted")
        synchronized(lock) { accrueLocked(); job = null }
        persistNow()
        publishCooldown()
        onExhausted()
    }

    private fun accrueLocked() {
        val now = elapsedClock()
        val delta = now - lastAccrueElapsed
        lastAccrueElapsed = now
        state = limiter.accrue(state, delta, wallClock(), now)
    }

    /** Fire-and-forget, but survives cancellation of [scope] (teardown cancels it). */
    private fun persist() {
        scope.launch(NonCancellable) { persistNow() }
    }

    private suspend fun persistNow() {
        withContext(NonCancellable) {
            saveMutex.withLock {
                val snapshot = synchronized(lock) { state }
                runCatching { repo.saveLimiterState(snapshot) }.onFailure { Log.w(TAG, "persist failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "AirPlayLimiter"
        const val TICK_MS = 10_000L
    }
}
