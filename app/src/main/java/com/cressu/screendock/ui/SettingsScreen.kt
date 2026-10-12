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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cressu.screendock.R
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.settings.PinMode
import com.cressu.screendock.settings.SettingsRepository
import com.cressu.screendock.ui.components.AuroraBackground
import com.cressu.screendock.ui.components.GlassIconButton
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.components.Segment
import com.cressu.screendock.ui.components.SegmentedControl
import com.cressu.screendock.ui.components.SettingsCard
import com.cressu.screendock.ui.components.Spacer16
import com.cressu.screendock.ui.components.Spacer8
import com.cressu.screendock.ui.components.SwitchRow
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings screen. Most edits are staged locally and persisted by "Apply &
 * restart receiver" (they need a re-advertise); the scale mode and the
 * receiver run toggle take effect immediately.
 */
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    pro: ProUiState,
    onApply: () -> Unit,
    onBack: () -> Unit,
    onBuyPro: () -> Unit,
    onOpenTroubleshooting: () -> Unit,
    onToggleReceiver: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AirPlaySettings()) }
    var initial by remember { mutableStateOf<AirPlaySettings?>(null) }
    val receiverEnabled by AirPlayService.receiverEnabled.collectAsState()

    LaunchedEffect(Unit) {
        val loaded = repo.current()
        settings = loaded
        initial = loaded
    }
    val pinOk = settings.pinMode != PinMode.FIXED || PinValidator.isValidFixedPin(settings.fixedPin)
    val dirty = initial != null && settings != initial
    val canApply = dirty && pinOk

    val backFocus = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    LaunchedEffect(isTv) {
        if (isTv) { delay(150); runCatching { backFocus.requestFocus() } }
    }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            onClick = onBack,
                            focusRequester = backFocus,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.set_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = Palette.textPrimary,
                            )
                            Text(
                                stringResource(R.string.set_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.textTertiary,
                            )
                        }
                    }

                    ProCard(pro = pro, onBuy = onBuyPro)

                    ReceiverCard(
                        settings = settings,
                        receiverEnabled = receiverEnabled,
                        onChange = { settings = it },
                        onToggleReceiver = onToggleReceiver,
                    )

                    DisplayCard(
                        scaleMode = ScaleModes.parse(settings.scaleMode),
                        onSelect = { mode ->
                            // Live: persist + apply to the running renderer; not part of "dirty".
                            AirPlayService.videoRenderer?.scaleMode = mode
                            settings = settings.copy(scaleMode = mode.name)
                            initial = initial?.copy(scaleMode = mode.name)
                            scope.launch { repo.updateScaleMode(mode.name) }
                        },
                    )

                    VideoQualityCard(settings = settings, onChange = { settings = it })

                    MediaCard(settings = settings, onChange = { settings = it })

                    CodecsCard(settings = settings, onChange = { settings = it })

                    SecurityCard(settings = settings, onChange = { settings = it })

                    SettingsCard(title = stringResource(R.string.ts_title), icon = Icons.Rounded.Wifi) {
                        Text(
                            stringResource(R.string.ts_entry_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textSecondary,
                        )
                        Spacer16()
                        GradientButton(
                            text = stringResource(R.string.ts_entry_button),
                            icon = Icons.Rounded.Wifi,
                            muted = true,
                            onClick = onOpenTroubleshooting,
                        )
                    }

                    GradientButton(
                        text = stringResource(R.string.set_apply),
                        icon = Icons.Rounded.Cast,
                        muted = !canApply,
                        onClick = {
                            if (!canApply) return@GradientButton
                            scope.launch {
                                persist(repo, settings)
                                onApply()
                            }
                        },
                    )
                    Text(
                        stringResource(if (dirty && !pinOk) R.string.pin_field_error else R.string.set_apply_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dirty && !pinOk) Palette.error else Palette.textTertiary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private suspend fun persist(repo: SettingsRepository, s: AirPlaySettings) {
    repo.updateDisplayName(s.displayName.ifBlank { "ScreenDock" })
    repo.updateResolution(s.maxWidth, s.maxHeight)
    repo.updateFps(s.maxFps)
    repo.updateH265(s.h265Enabled)
    repo.updatePinMode(s.pinMode)
    repo.updateFixedPin(s.fixedPin)
    repo.updateAskBeforeAllow(s.askBeforeAllow)
    repo.updateAutoStartOnBoot(s.autoStartOnBoot)
    repo.updateAutoOpenOnConnect(s.autoOpenOnConnect)
    repo.updateHlsEnabled(s.hlsEnabled)
    repo.updateKeepScreenOnAudio(s.keepScreenOnAudio)
}

@Composable
private fun ProCard(pro: ProUiState, onBuy: () -> Unit) {
    SettingsCard(title = stringResource(R.string.pro_card_title), icon = Icons.Rounded.Star) {
        if (pro.unlocked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Palette.ready,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.pro_unlocked),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.textPrimary,
                )
            }
        } else {
            Text(
                stringResource(R.string.pro_card_body, AirPlayService.FREE_SESSION_MINUTES),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.textSecondary,
            )
            Spacer16()
            ProPurchaseButton(pro = pro, onBuy = onBuy)
        }
    }
}

@Composable
private fun VideoQualityCard(settings: AirPlaySettings, onChange: (AirPlaySettings) -> Unit) {
    SettingsCard(title = stringResource(R.string.set_card_quality), icon = Icons.Rounded.Tv) {
        Text(stringResource(R.string.set_max_res), style = MaterialTheme.typography.labelMedium, color = Palette.textSecondary)
        Spacer8()
        SegmentedControl(
            selected = settings.maxWidth to settings.maxHeight,
            segments = listOf(
                Segment(0 to 0, "Native"),
                Segment(1280 to 720, "720p"),
                Segment(1920 to 1080, "1080p"),
                Segment(3840 to 2160, "4K"),
            ),
            onSelect = { (w, h) -> onChange(settings.copy(maxWidth = w, maxHeight = h)) },
        )
        Spacer16()
        Text(stringResource(R.string.set_fps), style = MaterialTheme.typography.labelMedium, color = Palette.textSecondary)
        Spacer8()
        SegmentedControl(
            selected = settings.maxFps,
            segments = listOf(Segment(30, "30 fps"), Segment(60, "60 fps")),
            onSelect = { fps -> onChange(settings.copy(maxFps = fps)) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.set_native_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun CodecsCard(settings: AirPlaySettings, onChange: (AirPlaySettings) -> Unit) {
    SettingsCard(title = stringResource(R.string.set_card_codecs), icon = Icons.Rounded.Memory) {
        SwitchRow(
            title = "H.265 / HEVC",
            description = stringResource(R.string.set_h265_desc),
            checked = settings.h265Enabled,
            onCheckedChange = { onChange(settings.copy(h265Enabled = it)) },
        )
    }
}
