package com.cressu.screendock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.settings.SettingsRepository
import com.cressu.screendock.ui.components.AuroraBackground
import com.cressu.screendock.ui.components.GlassIconButton
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.components.Segment
import com.cressu.screendock.ui.components.SegmentedControl
import com.cressu.screendock.ui.components.SettingsCard
import com.cressu.screendock.ui.components.Spacer16
import com.cressu.screendock.ui.components.Spacer8
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Settings screen (FR-7). Edits display name, max resolution, max frame rate,
 * and H.265 toggle. Changes are persisted to DataStore; "Apply" restarts the
 * receiver service so the new values take effect (re-advertise mDNS, set caps).
 */
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    onApply: () -> Unit,
    onBack: () -> Unit,
    proUnlocked: Boolean = false,
    onBuyPro: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AirPlaySettings()) }
    var initial by remember { mutableStateOf<AirPlaySettings?>(null) }

    LaunchedEffect(Unit) {
        val loaded = repo.current()
        settings = loaded
        initial = loaded
    }
    val dirty = initial != null && settings != initial

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    // --- Header ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            onClick = onBack,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Settings",
                                style = MaterialTheme.typography.titleLarge,
                                color = Palette.textPrimary,
                            )
                            Text(
                                "Receiver preferences",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.textTertiary,
                            )
                        }
                    }

                    // --- ScreenDock Pro ---
                    SettingsCard(title = "ScreenDock Pro", icon = Icons.Rounded.Star) {
                        if (proUnlocked) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Palette.ready,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Unlocked — thank you for supporting ScreenDock!",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Palette.textPrimary,
                                )
                            }
                        } else {
                            Text(
                                "Unlimited mirroring sessions. The free tier limits each session to ${AirPlayService.FREE_SESSION_MINUTES} minutes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.textSecondary,
                            )
                            Spacer16()
                            GradientButton(
                                text = "Unlock Pro — $9.99",
                                icon = Icons.Rounded.Star,
                                onClick = onBuyPro,
                            )
                        }
                    }

                    // --- Receiver identity ---
                    SettingsCard(title = "Receiver", icon = Icons.Rounded.Edit) {
                        Text(
                            "Device name",
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.textSecondary,
                        )
                        Spacer8()
                        OutlinedTextField(
                            value = settings.displayName,
                            onValueChange = {
                                if (it.length <= 32) settings = settings.copy(displayName = it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Palette.textPrimary,
                                unfocusedTextColor = Palette.textPrimary,
                                cursorColor = Palette.accent,
                                focusedBorderColor = Palette.accent.copy(alpha = 0.65f),
                                unfocusedBorderColor = Palette.white.copy(alpha = 0.10f),
                                focusedContainerColor = Palette.white.copy(alpha = 0.05f),
                                unfocusedContainerColor = Palette.white.copy(alpha = 0.05f),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This is the name senders see when they pick a device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textTertiary,
                        )
                    }

                    // --- Video quality ---
                    SettingsCard(title = "Video quality", icon = Icons.Rounded.Tv) {
                        Text(
                            "Max resolution",
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.textSecondary,
                        )
                        Spacer8()
                        SegmentedControl(
                            selected = settings.maxWidth to settings.maxHeight,
                            segments = listOf(
                                Segment(0 to 0, "Native"),
                                Segment(1280 to 720, "720p"),
                                Segment(1920 to 1080, "1080p"),
                                Segment(3840 to 2160, "4K"),
                            ),
                            onSelect = { (w, h) -> settings = settings.copy(maxWidth = w, maxHeight = h) },
                        )
                        Spacer16()
                        Text(
                            "Frame rate",
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.textSecondary,
                        )
                        Spacer8()
                        SegmentedControl(
                            selected = settings.maxFps,
                            segments = listOf(
                                Segment(30, "30 fps"),
                                Segment(60, "60 fps"),
                                Segment(120, "120 fps"),
                            ),
                            onSelect = { fps -> settings = settings.copy(maxFps = fps) },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "“Native” matches this display's panel resolution.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textTertiary,
                        )
                    }

                    // --- Codecs ---
                    SettingsCard(title = "Codecs", icon = Icons.Rounded.Memory) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "H.265 / HEVC",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Palette.textPrimary,
                                )
                                Text(
                                    "Sharper video at the same bitrate. Turn off if a sender has trouble connecting.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.textSecondary,
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Switch(
                                checked = settings.h265Enabled,
                                onCheckedChange = { settings = settings.copy(h265Enabled = it) },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = Palette.accent,
                                    checkedThumbColor = Palette.white,
                                    uncheckedTrackColor = Palette.white.copy(alpha = 0.14f),
                                    uncheckedThumbColor = Palette.textSecondary,
                                    uncheckedBorderColor = Palette.white.copy(alpha = 0.18f),
                                ),
                            )
                        }
                    }

                    // --- Apply ---
                    GradientButton(
                        text = "Apply & restart receiver",
                        icon = Icons.Rounded.Cast,
                        muted = !dirty,
                        onClick = {
                            scope.launch {
                                repo.updateDisplayName(settings.displayName.ifBlank { "ScreenDock" })
                                repo.updateResolution(settings.maxWidth, settings.maxHeight)
                                repo.updateFps(settings.maxFps)
                                repo.updateH265(settings.h265Enabled)
                                onApply()
                            }
                        },
                    )
                    Text(
                        "Restarts the receiver to re-advertise — senders reconnect automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textTertiary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}
