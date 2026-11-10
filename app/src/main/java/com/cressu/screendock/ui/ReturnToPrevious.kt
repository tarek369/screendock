package com.cressu.screendock.ui

/**
 * Remembers whether the receiver UI was brought to the front BY a connection ("summoned") and
 * decides when to step aside so the previous app (or TV input) comes back after the last sender
 * disconnects. A screen the user opened by hand is never sent away.
 *
 * Pure state machine driven by MainActivity's lifecycle; unit-tested.
 * Adapted from jqssun/android-airplay-server PR #64 (and #88), GPL-3.0.
 */
class ReturnToPreviousTracker {
    var summoned = false
        private set
    var returnPending = false
        private set
    private var summonPending = false
    private var wasStopped = false

    /** An intent arrived (onCreate / onNewIntent); [summon] = it carried the session-summon extra. */
    fun onIntent(summon: Boolean) {
        if (summon) {
            summoned = true
            summonPending = true
        }
    }

    /** A resume that was not caused by a summon intent after being stopped = manual open. */
    fun onResume() {
        if (!summonPending && wasStopped) {
            summoned = false
            returnPending = false
        }
        summonPending = false
        wasStopped = false
    }

    fun onStop() { wasStopped = true }

    /** Last sender left. Returns true when a return should be armed (after the grace period). */
    fun onSessionEnded(settingOn: Boolean): Boolean {
        if (!summoned || !settingOn) return false
        returnPending = true
        return true
    }

    /** A sender (re)connected inside the grace period: stay. */
    fun onSessionStarted() { returnPending = false }

    /** Called when the grace period elapses; true = move the task to the back now. */
    fun consumeReturn(connected: Boolean, settingOn: Boolean): Boolean {
        val go = returnPending && summoned && !connected && settingOn
        if (go) {
            returnPending = false
            summoned = false
        }
        return go
    }

    companion object {
        /** A quick stop/play on the sender must not flash the screen below. */
        const val GRACE_MS = 2_000L
    }
}
