package com.cressu.screendock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.cressu.screendock.settings.SettingsRepository

/**
 * Optional autostart at boot (setting autoStartOnBoot, off by default).
 *
 * Policy (targetSdk 36):
 *  - Android 15+ forbids starting mediaPlayback / dataSync / camera / microphone / phoneCall /
 *    mediaProjection foreground services from BOOT_COMPLETED. We start the service with only the
 *    `connectedDevice` FGS type in that case (allowed from boot; ScreenDock declares
 *    CHANGE_WIFI_MULTICAST_STATE which satisfies that type's permission prerequisite) and keep it
 *    that way until the user opens the app.
 *  - If the start is still rejected (OEM behaviour, notifications denied...) we never crash:
 *    the exception is caught and a "Tap to start ScreenDock" notification is posted instead.
 *  - Respects an explicit user Stop (receiverEnabled=false).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return // storage still encrypted; wait for BOOT_COMPLETED
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val s = SettingsRepository(app).current()
                if (s.autoStartOnBoot && ReceiverPrefs.isEnabled(app)) {
                    AirPlayService.startFromBoot(app)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "boot autostart failed", t)
                runCatching { ReceiverNotification(app).apply { createChannels() }.notifyBootPrompt() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "AirPlayBoot" }
}
