package com.cressu.screendock.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cressu.screendock.R
import com.cressu.screendock.logs.LogCapture
import com.cressu.screendock.logs.LogLevel
import com.cressu.screendock.logs.LogLine
import com.cressu.screendock.logs.LogReport
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.ui.components.AuroraBackground
import com.cressu.screendock.ui.components.GlassIconButton
import com.cressu.screendock.ui.components.Segment
import com.cressu.screendock.ui.components.SegmentedControl
import com.cressu.screendock.ui.theme.Palette
import com.cressu.screendock.logs.LogFiles
import androidx.compose.material.icons.rounded.Email
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val TAG = "AirplayLogs"
private const val REFRESH_MS = 500L

/** Header lines (app, device, settings summary) for a shared report. Never includes a PIN. */
fun logReportHeader(d: SupportDiagnostics, settings: AirPlaySettings): List<String> = listOf(
    "App: ScreenDock ${d.appVersion} (${d.versionCode}), ${if (d.pro) "Pro" else "Free"}",
    "Device: ${d.manufacturer} ${d.model}",
    "Android: ${d.androidRelease} (API ${d.sdkInt})",
    "Network: ${d.network}, VPN ${if (d.vpnOn) "on" else "off"}",
    "Receiver: ${if (d.receiverRunning) "running" else "stopped"}",
) + LogReport.settingsSummary(
    name = settings.displayName,
    resolution = d.resolution,
    fps = d.fps,
    h265 = settings.h265Enabled,
    pinMode = settings.pinMode.name,
    hls = settings.hlsEnabled,
    askBeforeAllow = settings.askBeforeAllow,
    scaleMode = settings.scaleMode,
)

@Composable
fun LogsScreen(header: () -> List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    var minLevel by rememberSaveable { mutableStateOf(LogLevel.VERBOSE) }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    val listState = rememberLazyListState()
    val backFocus = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    val emailPrompt = stringResource(R.string.support_email_prompt)
    val noEmailApp = stringResource(R.string.support_no_email_app, SupportEmail.ADDRESS)

    LaunchedEffect(isTv) {
        if (isTv) { delay(150); runCatching { backFocus.requestFocus() } }
    }
    LaunchedEffect(minLevel) {
        LogCapture.start()
        var seen = -1L
        while (true) {
            val total = LogCapture.buffer.totalAppended()
            if (total != seen) {
                seen = total
                val following = !listState.canScrollForward
                lines = LogCapture.buffer.snapshot(minLevel)
                if (following && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
            }
            delay(REFRESH_MS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    onClick = onBack,
                    focusRequester = backFocus,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.logs_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                GlassIconButton(
                    icon = Icons.Rounded.DeleteSweep,
                    contentDescription = stringResource(R.string.logs_clear),
                    onClick = { LogCapture.buffer.clear(); lines = emptyList() },
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    icon = Icons.Rounded.Email,
                    contentDescription = stringResource(R.string.logs_email),
                    onClick = {
                        val h = header()
                        val logs = captureSupportLogs(h)
                        val body = (listOf(emailPrompt, "", "", "--- Please keep the details below ---") + h).joinToString("\n")
                        if (!launchSupportEmail(context, "ScreenDock: logs", body, logs)) {
                            Toast.makeText(context, noEmailApp, Toast.LENGTH_LONG).show()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    icon = Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.logs_share),
                    onClick = {
                        val text = LogReport.format(header(), LogCapture.buffer.snapshot(minLevel), now())
                        if (!shareLogs(context, text)) {
                            Toast.makeText(context, R.string.logs_share_failed, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
            SegmentedControl(
                selected = minLevel,
                segments = listOf(
                    Segment(LogLevel.VERBOSE, stringResource(R.string.logs_level_all)),
                    Segment(LogLevel.INFO, stringResource(R.string.logs_level_info)),
                    Segment(LogLevel.WARN, stringResource(R.string.logs_level_warn)),
                    Segment(LogLevel.ERROR, stringResource(R.string.logs_level_error)),
                ),
                onSelect = { minLevel = it },
            )
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.white.copy(alpha = 0.05f)),
            ) {
                if (lines.isEmpty()) {
                    Text(
                        stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        items(lines) { line ->
                            Text(
                                line.raw,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = levelColor(line.level),
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> Palette.error
    LogLevel.WARN -> Palette.warn
    LogLevel.INFO -> Palette.textPrimary
    else -> Palette.textTertiary
}

private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

/** Writes the report to the app cache and opens the share sheet. Returns false on failure. */
private fun shareLogs(context: Context, text: String): Boolean {
    return try {
        val uri = LogFiles.write(context, text)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ScreenDock logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no app to share logs", e)
        false
    } catch (e: Exception) {
        Log.w(TAG, "share logs failed", e)
        false
    }
}
