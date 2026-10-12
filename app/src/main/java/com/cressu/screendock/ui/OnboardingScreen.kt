package com.cressu.screendock.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cressu.screendock.R
import com.cressu.screendock.ui.components.AuroraBackground
import com.cressu.screendock.ui.components.GlassCard
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.components.IconBadge
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3

fun Context.isIgnoringBatteryOptimizations(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(packageName) == true

fun Context.notificationsGranted(): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Opens the system battery-optimization list (needs no permission, unlike
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). The user picks ScreenDock → "Don't optimize".
 */
fun Context.openBatteryOptimizationSettings() {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: Exception) {
        startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    deviceName: String,
    onRequestNotifications: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val resumed by rememberIsResumed()
    // Re-evaluated whenever the app returns from a system settings screen.
    val batteryOk = remember(resumed) { context.isIgnoringBatteryOptimizations() }
    val notifOk = remember(resumed) { context.notificationsGranted() }
    val last = pager.currentPage == PAGE_COUNT - 1

    val nextFocus = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    LaunchedEffect(pager.currentPage, isTv) {
        if (!isTv) return@LaunchedEffect
        delay(150)
        runCatching { nextFocus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!last) {
                    GradientButton(
                        text = stringResource(R.string.onb_skip),
                        muted = true,
                        onClick = onDone,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = true,
            ) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (page) {
                        0 -> InfoPage(
                            icon = Icons.Rounded.Wifi,
                            title = stringResource(R.string.onb1_title),
                            body = stringResource(R.string.onb1_body),
                            tip = stringResource(R.string.onb1_tip),
                        )
                        1 -> InfoPage(
                            icon = Icons.Rounded.ScreenShare,
                            title = stringResource(R.string.onb2_title),
                            body = stringResource(R.string.onb2_body, deviceName),
                            tip = stringResource(R.string.onb2_tip),
                        )
                        else -> PermissionsPage(
                            batteryOk = batteryOk,
                            notifOk = notifOk,
                            onBattery = { context.openBatteryOptimizationSettings() },
                            onNotifications = onRequestNotifications,
                        )
                    }
                }
            }

            PagerDots(current = pager.currentPage)
            Spacer(Modifier.height(16.dp))
            GradientButton(
                text = stringResource(if (last) R.string.onb_done else R.string.onb_next),
                onClick = {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                modifier = Modifier.widthIn(max = 420.dp),
                focusRequester = nextFocus,
            )
        }
    }
}

@Composable
private fun PagerDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGE_COUNT) { i ->
            Box(
                Modifier
                    .size(if (i == current) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (i == current) Palette.accent else Palette.white.copy(alpha = 0.25f)),
            )
        }
    }
}

@Composable
private fun InfoPage(icon: ImageVector, title: String, body: String, tip: String) {
    GlassCard(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        IconBadge(icon)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Palette.textSecondary)
        Spacer(Modifier.height(14.dp))
        Text(tip, style = MaterialTheme.typography.bodySmall, color = Palette.textTertiary)
    }
}

@Composable
private fun PermissionsPage(
    batteryOk: Boolean,
    notifOk: Boolean,
    onBattery: () -> Unit,
    onNotifications: () -> Unit,
) {
    GlassCard(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        IconBadge(Icons.Rounded.BatteryChargingFull)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onb3_title), style = MaterialTheme.typography.titleLarge, color = Palette.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.onb3_body), style = MaterialTheme.typography.bodyLarge, color = Palette.textSecondary)
        Spacer(Modifier.height(18.dp))
        PermissionRow(
            ok = batteryOk,
            icon = Icons.Rounded.BatteryChargingFull,
            okText = stringResource(R.string.onb3_battery_ok),
            actionText = stringResource(R.string.onb3_battery_action),
            onAction = onBattery,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                ok = notifOk,
                icon = Icons.Rounded.Notifications,
                okText = stringResource(R.string.onb3_notif_ok),
                actionText = stringResource(R.string.onb3_notif_action),
                onAction = onNotifications,
            )
        }
    }
}

@Composable
private fun PermissionRow(ok: Boolean, icon: ImageVector, okText: String, actionText: String, onAction: () -> Unit) {
    if (ok) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Palette.ready, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
            Text(okText, style = MaterialTheme.typography.bodyLarge, color = Palette.textPrimary, textAlign = TextAlign.Start)
        }
    } else {
        GradientButton(text = actionText, icon = icon, muted = true, onClick = onAction)
    }
}
