package com.cressu.screendock.service

import android.graphics.Bitmap

/** Receiver states surfaced to the UI. */
enum class ReceiverState { IDLE, WAITING, CONNECTED }

/** What the connected sender is currently streaming. VIDEO = AirPlay video (HLS URL) playback. */
enum class MediaMode { NONE, MIRROR, AUDIO, VIDEO }

/** Now-playing info for music/video casts. [cover] is downscaled to at most 512 px. */
data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: Bitmap? = null,
    val positionSec: Float = 0f,
    val durationSec: Float = 0f,
)

/** Pure reducers for [NowPlaying] (unit-tested). */
object NowPlayingReducer {
    /** New metadata. The old cover is kept only when the album is unchanged; progress resets on a new track. */
    fun withMetadata(old: NowPlaying?, title: String?, artist: String?, album: String?): NowPlaying {
        if (old != null && old.title == title && old.artist == artist && old.album == album) return old
        val keepCover = old != null && album != null && old.album == album
        return NowPlaying(title, artist, album, if (keepCover) old?.cover else null, 0f, 0f)
    }

    /** Degenerate progress (duration <= 0, sent on pause) is ignored. */
    fun withProgress(old: NowPlaying?, positionSec: Float, durationSec: Float): NowPlaying {
        val base = old ?: NowPlaying()
        if (durationSec <= 0f || positionSec < 0f) return base
        return base.copy(positionSec = positionSec, durationSec = durationSec)
    }

    fun withCover(old: NowPlaying?, cover: Bitmap?): NowPlaying = (old ?: NowPlaying()).copy(cover = cover)
}
