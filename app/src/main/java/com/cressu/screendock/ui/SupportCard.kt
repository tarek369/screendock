package com.cressu.screendock.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.cressu.screendock.R
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.ui.components.FieldLabel
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.components.Segment
import com.cressu.screendock.ui.components.SegmentedControl
import com.cressu.screendock.ui.components.SettingsCard
import com.cressu.screendock.ui.components.focusGlow
import com.cressu.screendock.ui.theme.Palette

private const val TAG = "AirplaySupport"

/** "Still not working? Tell us" — picks problem + sender, then opens a prefilled email. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TellUsCard(diagnostics: () -> SupportDiagnostics) {
    val context = LocalContext.current
    var problem by rememberSaveable { mutableStateOf(SupportProblem.NOT_FOUND) }
    var sender by rememberSaveable { mutableStateOf(SenderKind.IPHONE) }
    val prompt = stringResource(R.string.support_email_prompt)
    val noEmailApp = stringResource(R.string.support_no_email_app, SupportEmail.ADDRESS)
    val problems = listOf(
        SupportProblem.NOT_FOUND to stringResource(R.string.support_problem_not_found),
        SupportProblem.DROPS to stringResource(R.string.support_problem_drops),
        SupportProblem.BLACK_SCREEN to stringResource(R.string.support_problem_black),
        SupportProblem.NO_SOUND to stringResource(R.string.support_problem_sound),
        SupportProblem.OTHER to stringResource(R.string.support_problem_other),
    )

    SettingsCard(title = stringResource(R.string.support_title), icon = Icons.Rounded.SupportAgent) {
        Text(
            stringResource(R.string.support_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        FieldLabel(stringResource(R.string.support_problem_label))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            problems.forEach { (value, label) ->
                ProblemChip(label, selected = value == problem, onClick = { problem = value })
            }
        }
        Spacer(Modifier.height(16.dp))
        FieldLabel(stringResource(R.string.support_sender_label))
        SegmentedControl(
            selected = sender,
            segments = SenderKind.entries.map { Segment(it, it.label) },
            onSelect = { sender = it },
        )
        Spacer(Modifier.height(18.dp))
        GradientButton(
            text = stringResource(R.string.support_send),
            icon = Icons.Rounded.Email,
            onClick = {
                val subject = SupportEmail.subject(problem, sender)
                val body = SupportEmail.body(prompt, problem, sender, diagnostics())
                if (!launchSupportEmail(context, subject, body)) {
                    Toast.makeText(context, noEmailApp, Toast.LENGTH_LONG).show()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.support_note),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun ProblemChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Palette.onAccent else Palette.textPrimary,
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .focusGlow(shape, focusedScale = 1.03f)
            .clip(shape)
            .background(if (selected) Palette.accent else Palette.white.copy(alpha = 0.05f))
            .border(1.dp, if (selected) Palette.accent else Palette.white.copy(alpha = 0.14f), shape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** Opens the user's email app with a prefilled message. False when no app can handle it. */
fun launchSupportEmail(context: Context, subject: String, body: String): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(SupportEmail.mailtoUri(subject, body))).apply {
        // Some mail apps ignore the mailto query and read only the extras.
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SupportEmail.ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no email app for support mail", e)
        false
    }
}

/** Snapshot of this install for the support email. */
fun supportDiagnostics(
    context: Context,
    pro: Boolean,
    settings: AirPlaySettings,
    receiverRunning: Boolean,
    net: NetworkSummary,
): SupportDiagnostics {
    val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
        .onFailure { Log.w(TAG, "package info unavailable", it) }
        .getOrNull()
    val network = when (net.transport) {
        NetworkSummary.Transport.WIFI -> listOfNotNull("Wi-Fi", net.wifiBandGhz?.let { "$it GHz" }).joinToString(" ")
        NetworkSummary.Transport.ETHERNET -> "Ethernet"
        NetworkSummary.Transport.OTHER -> "other"
        NetworkSummary.Transport.NONE -> "none"
    }
    return SupportDiagnostics(
        appVersion = pkg?.versionName ?: "unknown",
        versionCode = pkg?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
        pro = pro,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        network = network,
        vpnOn = net.vpnOn,
        receiverRunning = receiverRunning,
        resolution = if (settings.maxWidth == 0) "native" else "${settings.maxWidth}x${settings.maxHeight}",
        fps = settings.maxFps,
        successfulSessions = settings.successfulSessions,
    )
}
