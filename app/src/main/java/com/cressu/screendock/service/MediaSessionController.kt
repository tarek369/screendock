package com.cressu.screendock.service

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import com.cressu.screendock.R

/**
 * Platform MediaSession so the lock screen / media notification show now-playing for audio casts.
 * Display-only: AirPlay remote control back to the sender (DACP) is not implemented, so no
 * transport actions are advertised. Thread-safe (synchronized); safe to call from native threads.
 */
class MediaSessionController(context: Context) {
    private val session = MediaSession(context.applicationContext, context.getString(R.string.mediasession_name))
    private var released = false

    val token: MediaSession.Token get() = session.sessionToken

    @Synchronized
    fun updateMetadata(np: NowPlaying) {
        if (released) return
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, np.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, np.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, np.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (np.durationSec > 0f) (np.durationSec * 1000).toLong() else -1L)
        if (np.cover != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, np.cover)
        session.setMetadata(md.build())
        updateProgress(np.positionSec)
    }

    @Synchronized
    fun updateProgress(positionSec: Float) {
        if (released) return
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, (positionSec * 1000).toLong(), 1f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    @Synchronized
    fun setActive(active: Boolean) {
        if (released) return
        if (session.isActive != active) session.isActive = active
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        runCatching { session.isActive = false; session.release() }
    }
}
