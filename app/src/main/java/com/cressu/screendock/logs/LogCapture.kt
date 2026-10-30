package com.cressu.screendock.logs

import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide log store. Reads this app's OWN logcat stream (`logcat --pid=<self>`), which
 * needs no permission, so Kotlin `Log.*` calls AND native lines forwarded by
 * `android_log_forward` (tag AirplayNative) are captured without touching the frozen JNI
 * callback contracts. The first read replays whatever the system log buffer still holds,
 * so lines from before the UI opened are included.
 * (Approach adapted from jqssun/android-airplay-server, GPL-3.0.)
 */
object LogCapture {
    private const val TAG = "AirplayLogCapture"

    val buffer = LogBuffer()
    private val started = AtomicBoolean(false)

    /** Idempotent; safe to call from every Activity start. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({ readLoop() }, "log-capture").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        val process = try {
            ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "logcat unavailable; in-app log viewer will be empty", e)
            started.set(false)
            return
        }
        try {
            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { buffer.append(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "log capture stopped", e)
        } finally {
            process.destroy()
            started.set(false)
        }
    }
}
