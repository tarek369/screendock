package com.cressu.screendock.service

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * "Ask before allowing" admission gate. [request] is called on the native httpd thread and may
 * block up to [timeoutMs] while the UI/notification answers via [answer]. Timeout, a newer
 * request from another sender, or [cancelAll] all deny.
 *
 * [request] takes the remembered-device set by value so this class stays free of persistence.
 */
class ApprovalGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private class Pending(val deviceId: String, val future: CompletableFuture<Boolean>)

    private val lock = Any()
    private var pending: Pending? = null

    /**
     * @param onPending called with the sender label when a prompt starts and with null when it ends.
     * @param onAllowed called (on the calling thread) when the user approves, to remember the device.
     * @return true to admit.
     */
    fun request(
        deviceId: String,
        label: String,
        askBeforeAllow: Boolean,
        rememberedDevices: Set<String>,
        onPending: (String?) -> Unit,
        onAllowed: (String) -> Unit,
    ): Boolean {
        if (!needsPrompt(askBeforeAllow, deviceId, rememberedDevices)) return true
        val mine = Pending(deviceId, CompletableFuture())
        synchronized(lock) {
            pending?.future?.complete(false) // supersede
            pending = mine
        }
        onPending(label)
        val allowed = try {
            mine.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { // timeout / interrupted
            false
        } finally {
            synchronized(lock) { if (pending === mine) pending = null }
        }
        // Only clear the prompt if nobody superseded it.
        synchronized(lock) { if (pending == null) onPending(null) }
        if (allowed) onAllowed(deviceId)
        return allowed
    }

    /** UI/notification answer; no-op without a pending prompt. */
    fun answer(allow: Boolean) {
        synchronized(lock) { pending?.future?.complete(allow) }
    }

    /** Deny whatever is pending (server stopping). */
    fun cancelAll() = answer(false)

    companion object {
        /** Native waits ~10 s; stay under it. */
        const val DEFAULT_TIMEOUT_MS = 9_000L

        /** Pure rule: prompt only when enabled and the sender was never approved before. */
        fun needsPrompt(askBeforeAllow: Boolean, deviceId: String, remembered: Set<String>): Boolean =
            askBeforeAllow && (deviceId.isBlank() || deviceId !in remembered)
    }
}
