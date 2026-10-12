package com.cressu.screendock.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cressu.screendock.R
import com.cressu.screendock.service.NowPlaying
import com.cressu.screendock.ui.components.AuroraBackground
import com.cressu.screendock.ui.components.StatusChip
import com.cressu.screendock.ui.theme.AccentGradient
import com.cressu.screendock.ui.theme.Palette

/**
 * Audio mode: big cover art, title / artist / album, progress, sender name,
 * on the aurora background. System bars stay visible (handled by the host).
 */
@Composable
fun NowPlayingScreen(nowPlaying: NowPlaying?, senderName: String?) {
    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Wide (tablet landscape / TV): cover left, details right. Narrow: stacked.
            val wide = maxWidth > maxHeight * 1.2f
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(nowPlaying?.cover, Modifier.fillMaxHeight(0.85f).aspectRatio(1f))
                    Details(nowPlaying, senderName, Modifier.widthIn(max = 520.dp), TextAlign.Start)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Cover(nowPlaying?.cover, Modifier.fillMaxWidth(0.82f).widthIn(max = 460.dp).aspectRatio(1f))
                    Spacer(Modifier.height(32.dp))
                    Details(nowPlaying, senderName, Modifier.widthIn(max = 520.dp), TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    val cd = stringResource(R.string.cd_cover_art)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Palette.white.copy(alpha = 0.06f))
            .border(1.dp, Palette.white.copy(alpha = 0.10f), shape)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Palette.accent.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxSize(0.4f),
            )
        }
    }
}

@Composable
private fun Details(np: NowPlaying?, senderName: String?, modifier: Modifier, align: TextAlign) {
    val horizontal = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    Column(modifier = modifier, horizontalAlignment = horizontal) {
        Text(
            text = np?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.np_unknown_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Palette.textPrimary,
            textAlign = align,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val artist = np?.artist?.takeIf { it.isNotBlank() }
        val album = np?.album?.takeIf { it.isNotBlank() }
        if (artist != null) {
            Spacer(Modifier.height(6.dp))
            Text(artist, style = MaterialTheme.typography.titleMedium, color = Palette.accent, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (album != null) {
            Spacer(Modifier.height(2.dp))
            Text(album, style = MaterialTheme.typography.bodyMedium, color = Palette.textSecondary, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(24.dp))
        ProgressBar(np?.positionSec ?: 0f, np?.durationSec ?: 0f)
        if (!senderName.isNullOrBlank()) {
            Spacer(Modifier.height(24.dp))
            StatusChip(
                text = stringResource(R.string.np_playing_from, senderName),
                color = Palette.ready,
                pulse = false,
            )
        }
    }
}

@Composable
private fun ProgressBar(positionSec: Float, durationSec: Float) {
    val fraction = ClockFormatter.fraction(positionSec, durationSec)
    val pos = ClockFormatter.format(positionSec)
    val dur = if (durationSec > 0f) ClockFormatter.format(durationSec) else "--:--"
    val cd = stringResource(R.string.cd_progress, pos, dur)
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = cd }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.white.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AccentGradient),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(pos, style = MaterialTheme.typography.labelMedium, color = Palette.textSecondary)
            Text(dur, style = MaterialTheme.typography.labelMedium, color = Palette.textSecondary)
        }
    }
}
