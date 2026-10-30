package com.cressu.screendock.renderer

import android.content.Context
import android.media.AudioManager

/**
 * Configuration of the optional Oboe low-latency audio output
 * (see `oboe_audio_engine.cpp`). Decoding stays in [AudioRenderer]; this only selects
 * the PCM sink.
 *
 * @property cushionMs fixed jitter cushion in ms; [CUSHION_AUTO] (0) = adaptive tuner.
 */
data class NativeAudioConfig(
    val enabled: Boolean = false,
    val cushionMs: Int = CUSHION_AUTO,
) {
    /** Cushion clamped to the supported range (settings are untrusted persisted data). */
    val effectiveCushionMs: Int get() = sanitizeCushion(cushionMs)

    companion object {
        const val CUSHION_AUTO = 0
        const val MAX_CUSHION_MS = 500

        /** Choices shown in Settings (0 = Auto). */
        val CUSHION_CHOICES: List<Int> = listOf(CUSHION_AUTO, 40, 80, 150, 300)

        fun sanitizeCushion(ms: Int): Int = ms.coerceIn(CUSHION_AUTO, MAX_CUSHION_MS)

        /** Snap an arbitrary stored value to the nearest offered choice. */
        fun nearestChoice(ms: Int): Int {
            val v = sanitizeCushion(ms)
            return CUSHION_CHOICES.minByOrNull { kotlin.math.abs(it - v) } ?: CUSHION_AUTO
        }
    }
}

/** Which PCM sink the renderer ends up using, decided per stream open. */
enum class AudioSink { AUDIO_TRACK, OBOE }

/** Pure sink-selection logic (unit-tested): Oboe only when requested and the engine opened. */
object AudioSinkSelector {
    fun select(config: NativeAudioConfig, engineHandle: Long): AudioSink =
        if (config.enabled && engineHandle != 0L) AudioSink.OBOE else AudioSink.AUDIO_TRACK
}

/**
 * Device native output (sampleRate, framesPerBurst) from AudioManager, as Oboe's OpenSL ES
 * backend (API < 27) needs them to size low-latency buffers. (0, 0) when unavailable.
 */
fun queryDeviceAudioOutput(context: Context): Pair<Int, Int> =
    runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        rate to burst
    }.getOrDefault(0 to 0)
