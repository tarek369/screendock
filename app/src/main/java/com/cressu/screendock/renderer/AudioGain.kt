package com.cressu.screendock.renderer

/**
 * Pure sender-volume -> playback-gain mapping (unit-tested).
 *
 * AirPlay senders report volume in dB: `-144` (or lower) mutes, `0` is full volume. With
 * "Sync sender volume" OFF the sender's value is ignored and we play at unity gain, so the
 * user controls loudness with the Android device's own volume buttons.
 */
object AudioGain {
    const val MIN_DB = -144.0f
    const val MAX_DB = 0.0f

    fun dbToLinear(db: Float): Float {
        val clamped = db.coerceIn(MIN_DB, MAX_DB)
        return when {
            clamped <= MIN_DB -> 0.0f
            clamped >= MAX_DB -> 1.0f
            else -> Math.pow(10.0, (clamped / 20.0).toDouble()).toFloat()
        }
    }

    /** Linear gain to apply given the last sender volume and the "Sync sender volume" setting. */
    fun effectiveGain(senderDb: Float, followSender: Boolean): Float =
        if (followSender) dbToLinear(senderDb) else 1.0f
}
