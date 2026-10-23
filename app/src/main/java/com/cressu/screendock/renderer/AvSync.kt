package com.cressu.screendock.renderer

/**
 * Shared playout clock for mirroring A/V sync.
 *
 * UxPlay hands us `ntp_time_local` for every video frame and audio packet: the
 * sender's timestamp already converted to this device's CLOCK_REALTIME via the
 * AirPlay NTP exchange. Both streams are presented at that time plus one fixed
 * [latencyNs], so they line up no matter how differently they are buffered.
 * We schedule on the monotonic clock ([monoNs], i.e. System.nanoTime), converting
 * with the current realtime−monotonic offset.
 */
class PlayoutClock(
    private val latencyNs: Long = DEFAULT_LATENCY_NS,
    private val realtimeNs: () -> Long = { System.currentTimeMillis() * 1_000_000L },
    private val monoNs: () -> Long = System::nanoTime,
) {
    /** Monotonic time at which something stamped [ptsLocalNs] should be seen/heard; null if unknown. */
    fun presentAtNs(ptsLocalNs: Long): Long? {
        if (ptsLocalNs <= 0L) return null
        return ptsLocalNs - (realtimeNs() - monoNs()) + latencyNs
    }

    /** Time from now until [presentAtNs]; negative = already late; null if unknown. */
    fun delayNs(ptsLocalNs: Long): Long? = presentAtNs(ptsLocalNs)?.minus(monoNs())

    companion object {
        /** Covers sender encode + Wi-Fi + decode for video, and audio arriving close to its slot. */
        const val DEFAULT_LATENCY_NS = 250_000_000L
    }
}

sealed interface AudioAction {
    data object Play : AudioAction
    data object Drop : AudioAction
    data class Wait(val ms: Long) : AudioAction
}

/** Pure scheduling rules (unit-tested); renderers apply them. */
object AvSyncPolicy {
    /** Beyond this the clock mapping is wrong (NTP jump, stale timestamps): don't trust it. */
    private const val MAX_VALID_DELAY_NS = 2_000_000_000L
    private const val MAX_VIDEO_HOLD_NS = 1_000_000_000L
    private const val AUDIO_LATE_DROP_NS = 80_000_000L
    private const val AUDIO_ON_TIME_NS = 2_000_000L
    const val MAX_AUDIO_WAIT_MS = 500L

    /**
     * How long to hold a decoded video frame before showing it. Only while audio is
     * playing: a silent desktop mirror stays at minimum latency for interactivity.
     */
    fun videoHoldMs(delayNs: Long?, audioActive: Boolean): Long {
        if (!audioActive || delayNs == null) return 0L
        if (delayNs <= 0L || delayNs > MAX_VIDEO_HOLD_NS) return 0L
        return delayNs / 1_000_000L
    }

    /**
     * What to do with an audio packet due in [delayNs] when [queuedNs] of audio is
     * already waiting in the AudioTrack ahead of it.
     */
    fun audioAction(delayNs: Long?, queuedNs: Long): AudioAction {
        if (delayNs == null || delayNs > MAX_VALID_DELAY_NS) return AudioAction.Play
        val waitNs = delayNs - queuedNs
        return when {
            waitNs < -AUDIO_LATE_DROP_NS -> AudioAction.Drop
            waitNs <= AUDIO_ON_TIME_NS -> AudioAction.Play
            else -> AudioAction.Wait((waitNs / 1_000_000L).coerceAtMost(MAX_AUDIO_WAIT_MS))
        }
    }
}
