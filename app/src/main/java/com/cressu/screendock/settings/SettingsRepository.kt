package com.cressu.screendock.settings

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cressu.screendock.service.LimiterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore-backed persisted settings (FR-7). Survives app restarts. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "airplay_settings")

/**
 * Device-local state that must NOT be restored by Android backup (see res/xml backup rules):
 * the cached Pro entitlement, the free-tier limiter state and the approved-sender list.
 */
private val Context.localStore: DataStore<Preferences> by preferencesDataStore(name = "airplay_local_state")

/** How the receiver asks senders for a PIN. */
enum class PinMode { OFF, RANDOM, FIXED }

data class AirPlaySettings(
    /**
     * Receiver name advertised over mDNS. The data-class default is the generic
     * fallback; [SettingsRepository] substitutes "ScreenDock (<model>)" for
     * fresh installs where the user never set a name.
     */
    val displayName: String = DisplayNames.FALLBACK,
    /** 0×0 = "Native": advertise the device's real panel resolution. */
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFps: Int = 60,
    val h265Enabled: Boolean = true,
    /** ScreenDock Pro entitlement (cached; source of truth is Play Billing). */
    val proUnlocked: Boolean = false,
    // ---- phase 2 ----
    val pinMode: PinMode = PinMode.OFF,
    /** 4 digits, used when [pinMode] == FIXED. */
    val fixedPin: String = "",
    val askBeforeAllow: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    /** ScaleMode name: FIT / FILL / STRETCH. */
    val scaleMode: String = "FIT",
    val hlsEnabled: Boolean = true,
    val keepScreenOnAudio: Boolean = false,
    val autoOpenOnConnect: Boolean = true,
    val onboardingDone: Boolean = false,
    /** Sessions of at least 60 s that ended normally (drives the review prompt). */
    val successfulSessions: Int = 0,
    val reviewPrompted: Boolean = false,
)

/** Pure helpers for the default receiver name. */
object DisplayNames {
    const val FALLBACK = "ScreenDock"
    private const val MAX_MODEL_LEN = 28

    /** "ScreenDock (Pixel 8)"; falls back to plain "ScreenDock" for blank models. */
    fun defaultFor(model: String?): String {
        val clean = model.orEmpty()
            .filter { !it.isISOControl() && it != '@' && it != '(' && it != ')' }
            .trim()
            .take(MAX_MODEL_LEN)
            .trim()
        return if (clean.isEmpty()) FALLBACK else "$FALLBACK ($clean)"
    }
}

class SettingsRepository(private val context: Context) {

    /** The user-facing name ("OnePlus Pad") when the OS exposes one; raw model id otherwise. */
    private fun userDeviceName(): String? =
        runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private object Keys {
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val MAX_WIDTH    = intPreferencesKey("max_width")
        val MAX_HEIGHT   = intPreferencesKey("max_height")
        val MAX_FPS      = intPreferencesKey("max_fps")
        val H265_ENABLED = intPreferencesKey("h265_enabled") // legacy int-encoded boolean
        val PRO_UNLOCKED = intPreferencesKey("pro_unlocked")

        val PIN_MODE = stringPreferencesKey("pin_mode")
        val FIXED_PIN = stringPreferencesKey("fixed_pin")
        val ASK_BEFORE_ALLOW = booleanPreferencesKey("ask_before_allow")
        val AUTO_START_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val SCALE_MODE = stringPreferencesKey("scale_mode")
        val HLS_ENABLED = booleanPreferencesKey("hls_enabled")
        val KEEP_SCREEN_ON_AUDIO = booleanPreferencesKey("keep_screen_on_audio")
        val AUTO_OPEN = booleanPreferencesKey("auto_open_on_connect")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SUCCESSFUL_SESSIONS = intPreferencesKey("successful_sessions")
        val REVIEW_PROMPTED = booleanPreferencesKey("review_prompted")
        val ALLOWED_DEVICES = stringSetPreferencesKey("allowed_devices")

        val LIM_USED = longPreferencesKey("limiter_used_ms")
        val LIM_LAST_END_WALL = longPreferencesKey("limiter_last_usage_end_wall")
        val LIM_CD_START_WALL = longPreferencesKey("limiter_cooldown_start_wall")
        val LIM_CD_START_ELAPSED = longPreferencesKey("limiter_cooldown_start_elapsed")
        val LIM_CD_DURATION = longPreferencesKey("limiter_cooldown_duration")
    }

    val settings: Flow<AirPlaySettings> = combine(context.dataStore.data, context.localStore.data) { p, local ->
        AirPlaySettings(
            displayName = p[Keys.DISPLAY_NAME]?.takeIf { it.isNotBlank() }
                ?: DisplayNames.defaultFor(userDeviceName()),
            maxWidth    = p[Keys.MAX_WIDTH]    ?: 0,
            maxHeight   = p[Keys.MAX_HEIGHT]   ?: 0,
            // Apple senders cap mirroring at 60 fps; clamp legacy 120 selections.
            maxFps      = (p[Keys.MAX_FPS] ?: 60).coerceAtMost(60),
            h265Enabled = (p[Keys.H265_ENABLED] ?: 1) == 1,
            // Falls back to the pre-split key so upgrading Pro users stay Pro while offline.
            proUnlocked = ((local[Keys.PRO_UNLOCKED] ?: p[Keys.PRO_UNLOCKED]) ?: 0) == 1,
            pinMode = p[Keys.PIN_MODE]?.let { n -> PinMode.values().firstOrNull { it.name == n } } ?: PinMode.OFF,
            fixedPin = p[Keys.FIXED_PIN] ?: "",
            askBeforeAllow = p[Keys.ASK_BEFORE_ALLOW] ?: false,
            autoStartOnBoot = p[Keys.AUTO_START_BOOT] ?: false,
            scaleMode = p[Keys.SCALE_MODE] ?: "FIT",
            hlsEnabled = p[Keys.HLS_ENABLED] ?: true,
            keepScreenOnAudio = p[Keys.KEEP_SCREEN_ON_AUDIO] ?: false,
            autoOpenOnConnect = p[Keys.AUTO_OPEN] ?: true,
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            successfulSessions = p[Keys.SUCCESSFUL_SESSIONS] ?: 0,
            reviewPrompted = p[Keys.REVIEW_PROMPTED] ?: false,
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
        context.localStore.edit { it[Keys.PRO_UNLOCKED] = if (owned) 1 else 0 }
        context.dataStore.edit { it.remove(Keys.PRO_UNLOCKED) } // drop the legacy (backed-up) copy
    }

    suspend fun updateH265(enabled: Boolean) {
        context.dataStore.edit { it[Keys.H265_ENABLED] = if (enabled) 1 else 0 }
    }

    // ---- phase 2 ----

    suspend fun updatePinMode(mode: PinMode) {
        context.dataStore.edit { it[Keys.PIN_MODE] = mode.name }
    }

    /** Stores digits only, at most 4 (invalid input is sanitized, never throws). */
    suspend fun updateFixedPin(pin: String) {
        val clean = pin.filter { it in '0'..'9' }.take(4)
        context.dataStore.edit { it[Keys.FIXED_PIN] = clean }
    }

    suspend fun updateAskBeforeAllow(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ASK_BEFORE_ALLOW] = enabled }
    }

    suspend fun updateAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START_BOOT] = enabled }
    }

    suspend fun updateScaleMode(mode: String) {
        context.dataStore.edit { it[Keys.SCALE_MODE] = mode }
    }

    suspend fun updateHlsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HLS_ENABLED] = enabled }
    }

    suspend fun updateKeepScreenOnAudio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON_AUDIO] = enabled }
    }

    suspend fun updateAutoOpenOnConnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_OPEN] = enabled }
    }

    suspend fun updateOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun updateReviewPrompted(prompted: Boolean) {
        context.dataStore.edit { it[Keys.REVIEW_PROMPTED] = prompted }
    }

    suspend fun incrementSuccessfulSessions() {
        context.dataStore.edit { it[Keys.SUCCESSFUL_SESSIONS] = (it[Keys.SUCCESSFUL_SESSIONS] ?: 0) + 1 }
    }

    // ---- remembered senders ("ask before allowing") ----

    suspend fun allowedDevices(): Set<String> =
        context.localStore.data.first()[Keys.ALLOWED_DEVICES] ?: emptySet()

    suspend fun addAllowedDevice(deviceId: String) {
        if (deviceId.isBlank()) return
        context.localStore.edit { it[Keys.ALLOWED_DEVICES] = (it[Keys.ALLOWED_DEVICES] ?: emptySet()) + deviceId }
    }

    suspend fun clearAllowedDevices() {
        context.localStore.edit { it.remove(Keys.ALLOWED_DEVICES) }
    }

    // ---- free-tier limiter persistence (see SessionLimiter) ----

    suspend fun loadLimiterState(): LimiterState {
        val p = context.localStore.data.first()
        return LimiterState(
            usedMs = p[Keys.LIM_USED] ?: 0L,
            lastUsageEndWallMs = p[Keys.LIM_LAST_END_WALL] ?: 0L,
            cooldownStartWallMs = p[Keys.LIM_CD_START_WALL] ?: 0L,
            cooldownStartElapsedMs = p[Keys.LIM_CD_START_ELAPSED] ?: 0L,
            cooldownDurationMs = p[Keys.LIM_CD_DURATION] ?: 0L,
        )
    }

    suspend fun saveLimiterState(s: LimiterState) {
        context.localStore.edit {
            it[Keys.LIM_USED] = s.usedMs
            it[Keys.LIM_LAST_END_WALL] = s.lastUsageEndWallMs
            it[Keys.LIM_CD_START_WALL] = s.cooldownStartWallMs
            it[Keys.LIM_CD_START_ELAPSED] = s.cooldownStartElapsedMs
            it[Keys.LIM_CD_DURATION] = s.cooldownDurationMs
        }
    }
}
