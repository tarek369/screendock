package com.cressu.screendock.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Audio focus for the RAOP audio path (mirroring audio + AirPlay music via AudioTrack/Oboe).
 * Without it, music already playing in another app kept going underneath an AirPlay session.
 * Adapted from jqssun/android-airplay-server PR #69 (GPL-3.0).
 *
 * - [acquire] is cheap and idempotent: call it for every audio packet; only the first call of
 *   a session asks for AUDIOFOCUS_GAIN, so well-behaved players pause themselves.
 * - [release] abandons focus when the session ends.
 * - Losing focus does NOT pause or duck our output: the sender controls playback, and we never
 *   re-request within the same session (no focus ping-pong with the app that took it).
 * - The HLS ExoPlayer path keeps its own focus handling (handleAudioFocus = true); it does not
 *   feed RAOP audio packets, so it never triggers [acquire].
 */
class AudioFocusController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var requested = false
    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "audio focus change: $change (playback continues; sender controls it)")
    }

    /** Request AUDIOFOCUS_GAIN once per session. [movie] = mirroring audio (vs music). */
    fun acquire(movie: Boolean) {
        val am = audioManager ?: return
        synchronized(lock) {
            if (requested) return
            requested = true
            val result = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(if (movie) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(listener, main)
                        .build()
                    request = req
                    am.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "requestAudioFocus failed", t)
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
            Log.i(TAG, "audio focus requested (movie=$movie) -> result=$result")
        }
    }

    /** Abandon focus at session end. Safe to call when nothing was requested. */
    fun release() {
        val am = audioManager ?: return
        synchronized(lock) {
            if (!requested) return
            requested = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    request?.let { am.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(listener)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "abandonAudioFocus failed", t)
            }
            request = null
            Log.i(TAG, "audio focus released")
        }
    }

    private companion object {
        const val TAG = "AirplayAudioFocus"
    }
}
