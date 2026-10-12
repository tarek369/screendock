package com.cressu.screendock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cressu.screendock.R
import com.cressu.screendock.ui.components.GlassCard
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.components.IconBadge
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * "Ask before allowing new devices" prompt. Big, D-pad friendly: two full-width
 * buttons stacked vertically; initial focus on Deny (the safe default).
 * Back = deny.
 */
@Composable
fun ApprovalDialog(senderName: String, onAnswer: (Boolean) -> Unit) {
    val denyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { denyFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = { onAnswer(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        GlassCard(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(Palette.bgRaised, RoundedCornerShape(24.dp)),
        ) {
            IconBadge(Icons.Rounded.Lock)
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.approval_title, senderName),
                style = MaterialTheme.typography.titleLarge,
                color = Palette.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.approval_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.textSecondary,
            )
            Spacer(Modifier.height(22.dp))
            GradientButton(
                text = stringResource(R.string.approval_allow),
                icon = Icons.Rounded.Check,
                onClick = { onAnswer(true) },
            )
            Spacer(Modifier.height(12.dp))
            GradientButton(
                text = stringResource(R.string.approval_deny),
                icon = Icons.Rounded.Close,
                muted = true,
                onClick = { onAnswer(false) },
                focusRequester = denyFocus,
            )
        }
    }
}

/**
 * Big on-screen PIN shown while a sender is pairing. Pure display (no pointer
 * handling), drawn above every screen so it is visible in any media mode.
 */
@Composable
fun PinOverlay(pin: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(modifier = Modifier.padding(24.dp).widthIn(max = 560.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.pin_overlay_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                ) {
                    pin.forEach { digit ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Palette.white.copy(alpha = 0.08f))
                                .border(1.dp, Palette.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                digit.toString(),
                                color = Palette.textPrimary,
                                fontSize = 56.sp,
                                style = MaterialTheme.typography.displaySmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.pin_overlay_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
