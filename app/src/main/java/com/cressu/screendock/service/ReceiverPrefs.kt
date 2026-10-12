package com.cressu.screendock.service

import android.content.Context

/**
 * Tiny synchronous store for the user's Start/Stop intent. SharedPreferences (not DataStore) so
 * the boot receiver and Activity can read it without suspending. Excluded from backup.
 */
object ReceiverPrefs {
    private const val FILE = "screendock_service"
    private const val KEY_ENABLED = "receiver_enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
