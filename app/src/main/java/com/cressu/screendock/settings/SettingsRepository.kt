package com.cressu.screendock.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore-backed persisted settings (FR-7). Survives app restarts. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "airplay_settings")

data class AirPlaySettings(
    val displayName: String = "ScreenDock",
    /** 0×0 = "Native": advertise the device's real panel resolution. */
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFps: Int = 60,
    val h265Enabled: Boolean = true,
    /** ScreenDock Pro entitlement (cached; source of truth is Play Billing). */
    val proUnlocked: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val MAX_WIDTH    = intPreferencesKey("max_width")
        val MAX_HEIGHT   = intPreferencesKey("max_height")
        val MAX_FPS      = intPreferencesKey("max_fps")
        val H265_ENABLED = intPreferencesKey("h265_enabled") // datastore has no boolean
        val PRO_UNLOCKED = intPreferencesKey("pro_unlocked")
    }

    val settings: Flow<AirPlaySettings> = context.dataStore.data.map { p ->
        AirPlaySettings(
            displayName = p[Keys.DISPLAY_NAME] ?: "ScreenDock",
            maxWidth    = p[Keys.MAX_WIDTH]    ?: 0,
            maxHeight   = p[Keys.MAX_HEIGHT]   ?: 0,
            maxFps      = p[Keys.MAX_FPS]       ?: 60,
            h265Enabled = (p[Keys.H265_ENABLED] ?: 1) == 1,
            proUnlocked = (p[Keys.PRO_UNLOCKED] ?: 0) == 1,
        )
    }

    suspend fun current(): AirPlaySettings = settings.first()

    suspend fun updateDisplayName(name: String) {
        context.dataStore.edit { it[Keys.DISPLAY_NAME] = name }
    }

    suspend fun updateResolution(w: Int, h: Int) {
        context.dataStore.edit {
            it[Keys.MAX_WIDTH] = w
            it[Keys.MAX_HEIGHT] = h
        }
    }

    suspend fun updateFps(fps: Int) {
        context.dataStore.edit { it[Keys.MAX_FPS] = fps }
    }

    suspend fun updateProUnlocked(owned: Boolean) {
        context.dataStore.edit { it[Keys.PRO_UNLOCKED] = if (owned) 1 else 0 }
    }

    suspend fun updateH265(enabled: Boolean) {
        context.dataStore.edit { it[Keys.H265_ENABLED] = if (enabled) 1 else 0 }
    }
}
