package com.cressu.screendock.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cressu.screendock.R
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.service.ReceiverState
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.ui.components.AuroraBackground
import com.cressu.screendock.ui.components.BroadcastHero
import com.cressu.screendock.ui.components.CapabilityChip
import com.cressu.screendock.ui.components.GlassCard
import com.cressu.screendock.ui.components.GlassIconButton
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.components.IconBadge
import com.cressu.screendock.ui.components.PulsingDot
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.delay

private val CARD_MAX_WIDTH = 620.dp

/** Inputs for the idle (waiting / cooldown / stopped) screen. */
data class IdleUiState(
    val version: String,
    val state: ReceiverState,
    val receiverEnabled: Boolean,
    val settings: AirPlaySettings,
    val selfTestOk: Boolean?,
    val pro: ProUiState,
    val sessionLimitHit: Boolean,
    val cooldownUntilMs: Long,
)

data class IdleActions(
    val onOpenSettings: () -> Unit,
    val onOpenHelp: () -> Unit,
    val onBuyPro: () -> Unit,
    val onDismissNudge: () -> Unit,
    val onStartReceiver: () -> Unit,
)

@Composable
fun IdleScreen(ui: IdleUiState, actions: IdleActions) {
    val failed = ui.selfTestOk == false
    val probing = ui.selfTestOk == null
    val ready = ui.state == ReceiverState.WAITING
    val stopped = !ui.receiverEnabled && !failed

    val nowMs = rememberNowMs(active = ui.cooldownUntilMs > 0L)
    val cooldownLeftMs = if (ui.pro.unlocked) 0L else (ui.cooldownUntilMs - nowMs).coerceAtLeast(0L)
    val inCooldown = cooldownLeftMs > 0L

    val statusText = when {
        failed -> stringResource(R.string.idle_status_core_failed)
        stopped -> stringResource(R.string.idle_status_stopped)
        probing -> stringResource(R.string.idle_status_waking)
        inCooldown -> stringResource(R.string.idle_status_cooldown)
        ready -> stringResource(R.string.idle_status_ready)
        else -> stringResource(R.string.idle_status_starting)
    }
    val statusColor = when {
        failed -> Palette.error
        stopped -> Palette.textSecondary
        probing || inCooldown -> Palette.warn
        ready -> Palette.ready
        else -> Palette.accent
    }

    val showNudge = ui.sessionLimitHit && !ui.pro.unlocked && !inCooldown
    val primaryFocus = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    val ctaFocused = inCooldown || showNudge
    val startFocused = !ctaFocused && stopped
    LaunchedEffect(isTv, ctaFocused, startFocused) {
        if (!isTv) return@LaunchedEffect
        delay(150)
        runCatching { primaryFocus.requestFocus() }
    }
    val displayName = ui.settings.displayName.ifBlank { "ScreenDock" }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BroadcastHero(rings = !failed && !stopped, dim = failed || stopped)

            Spacer(Modifier.height(20.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.displaySmall,
                color = Palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(statusColor, pulse = !failed && !stopped)
                Spacer(Modifier.width(10.dp))
                Text(statusText, style = MaterialTheme.typography.labelLarge, color = statusColor)
            }
            Spacer(Modifier.height(36.dp))

            val cardModifier = Modifier.fillMaxWidth().widthIn(max = CARD_MAX_WIDTH)
            when {
                inCooldown -> {
                    CooldownCard(
                        remainingMs = cooldownLeftMs,
                        pro = ui.pro,
                        onBuy = actions.onBuyPro,
                        focusRequester = primaryFocus,
                        modifier = cardModifier,
                    )
                    Spacer(Modifier.height(20.dp))
                }
                showNudge -> {
                    UpgradeNudgeCard(
                        pro = ui.pro,
                        onBuy = actions.onBuyPro,
                        onDismiss = actions.onDismissNudge,
                        focusRequester = primaryFocus,
                        modifier = cardModifier,
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            when {
                failed -> ErrorCard(cardModifier)
                stopped -> {
                    StoppedCard(
                        onStart = actions.onStartReceiver,
                        focusRequester = if (startFocused) primaryFocus else null,
                        modifier = cardModifier,
                    )
                }
                else -> ConnectGuideCard(deviceName = displayName, modifier = cardModifier)
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapabilityChip(resolutionLabel(ui.settings.maxWidth, ui.settings.maxHeight))
                CapabilityChip("${ui.settings.maxFps} fps")
                if (ui.settings.h265Enabled) CapabilityChip("H.265")
            }
            Spacer(Modifier.height(18.dp))
            Text(
                ui.version,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.textTertiary,
            )
        }

        // MUST be composed after the scrollable column: a verticalScroll
        // container hit-tests its full bounds and would otherwise sit ON TOP
        // of these buttons and swallow every tap (buttons appear dead).
        Row(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassIconButton(
                icon = Icons.Rounded.Wifi,
                contentDescription = stringResource(R.string.cd_troubleshooting),
                onClick = actions.onOpenHelp,
            )
            GlassIconButton(
                icon = Icons.Rounded.Tune,
                contentDescription = stringResource(R.string.cd_settings),
                onClick = actions.onOpenSettings,
                focusRequester = if (!ctaFocused && !startFocused) primaryFocus else null,
            )
        }
    }
}

/** Wall-clock ticker (1 Hz) — only runs while [active]. */
@Composable
private fun rememberNowMs(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return if (active) now else System.currentTimeMillis()
}

private fun resolutionLabel(w: Int, h: Int): String = when {
    w == 0 -> "Native"
    h >= 2160 -> "4K"
    h >= 1080 -> "1080p"
    else -> "720p"
}

@Composable
private fun CooldownCard(
    remainingMs: Long,
    pro: ProUiState,
    onBuy: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Rounded.Timer, tint = Palette.warn)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.cooldown_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.textPrimary,
                )
                Text(
                    stringResource(R.string.cooldown_body, CountdownFormatter.format(remainingMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        ProPurchaseButton(pro = pro, onBuy = onBuy, focusRequester = focusRequester)
    }
}

/** Upgrade nudge shown after a free-tier session hits the time limit. */
@Composable
private fun UpgradeNudgeCard(
    pro: ProUiState,
    onBuy: () -> Unit,
    onDismiss: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.nudge_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.nudge_body, AirPlayService.FREE_SESSION_MINUTES),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textSecondary,
                )
            }
            GlassIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_dismiss),
                onClick = onDismiss,
            )
        }
        Spacer(Modifier.height(14.dp))
        ProPurchaseButton(pro = pro, onBuy = onBuy, focusRequester = focusRequester)
    }
}

@Composable
private fun StoppedCard(onStart: () -> Unit, focusRequester: FocusRequester?, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Text(
            stringResource(R.string.stopped_title),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.stopped_body),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        GradientButton(
            text = stringResource(R.string.action_start_receiver),
            icon = Icons.Rounded.PlayArrow,
            onClick = onStart,
            focusRequester = focusRequester,
        )
    }
}

/** "Swipe down from the top-right… → Screen Mirroring → pick this device." */
@Composable
private fun ConnectGuideCard(deviceName: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Text(
            stringResource(R.string.guide_overline),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.textTertiary,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GuideStep(
                icon = Icons.Rounded.PhoneIphone,
                title = stringResource(R.string.guide_step1_title),
                subtitle = AnnotatedString(stringResource(R.string.guide_step1_body)),
                modifier = Modifier.weight(1f),
            )
            GuideStep(
                icon = Icons.Rounded.ScreenShare,
                title = stringResource(R.string.guide_step2_title),
                subtitle = AnnotatedString(stringResource(R.string.guide_step2_body)),
                modifier = Modifier.weight(1f),
            )
            val bold = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.W600).toSpanStyle()
            val pre = stringResource(R.string.guide_step3_pre)
            val post = stringResource(R.string.guide_step3_post)
            GuideStep(
                icon = Icons.Rounded.Cast,
                title = stringResource(R.string.guide_step3_title),
                subtitle = buildAnnotatedString {
                    append(pre)
                    withStyle(bold) { append(deviceName) }
                    append(post)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.guide_same_wifi),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun GuideStep(
    icon: ImageVector,
    title: String,
    subtitle: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        IconBadge(icon)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.textSecondary)
    }
}

@Composable
private fun ErrorCard(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = Palette.error,
                modifier = Modifier.size(26.dp),
            )
            Column {
                Text(
                    stringResource(R.string.error_core_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.error_core_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textSecondary,
                )
            }
        }
    }
}
