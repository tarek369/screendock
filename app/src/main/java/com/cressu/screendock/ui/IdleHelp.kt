package com.cressu.screendock.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cressu.screendock.R
import com.cressu.screendock.ui.components.GlassCard
import com.cressu.screendock.ui.components.GradientButton
import com.cressu.screendock.ui.components.focusGlow
import com.cressu.screendock.ui.theme.Palette
import kotlinx.coroutines.delay

private const val NETWORK_POLL_MS = 3_000L

/**
 * Replaces the connect guide when the receiver has sat ready for a while with
 * no sender: live network checks first, then the two steps people miss.
 */
@Composable
internal fun ConnectHelpCard(
    deviceName: String,
    onMoreFixes: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var net by remember { mutableStateOf(NetworkInfoProvider.read(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NETWORK_POLL_MS)
            net = NetworkInfoProvider.read(context)
        }
    }

    GlassCard(modifier = modifier) {
        Text(stringResource(R.string.help_overline), style = MaterialTheme.typography.labelSmall, color = Palette.warn)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.help_title, deviceName),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.textPrimary,
        )
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (net.transport) {
                NetworkSummary.Transport.WIFI ->
                    HelpRow(Icons.Rounded.CheckCircle, Palette.ready, stringResource(R.string.help_wifi_ok), net.networkName)
                NetworkSummary.Transport.ETHERNET ->
                    HelpRow(Icons.Rounded.CheckCircle, Palette.ready, stringResource(R.string.help_ethernet_ok), null)
                else -> HelpRow(
                    Icons.Rounded.WifiOff,
                    Palette.error,
                    stringResource(R.string.help_no_wifi),
                    stringResource(R.string.help_no_wifi_body),
                )
            }
            if (net.vpnOn) {
                HelpRow(Icons.Rounded.WarningAmber, Palette.warn, stringResource(R.string.help_vpn), stringResource(R.string.help_vpn_body))
            }
            HelpStep(
                number = 1,
                title = net.networkName?.let { stringResource(R.string.help_same_wifi_named, it) }
                    ?: stringResource(R.string.help_same_wifi),
                body = stringResource(R.string.help_same_wifi_body),
            )
            HelpStep(
                number = 2,
                title = stringResource(R.string.help_mirror),
                body = stringResource(R.string.help_mirror_body, deviceName),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton(stringResource(R.string.help_more_fixes), onClick = onMoreFixes, modifier = Modifier.weight(1f))
            GradientButton(stringResource(R.string.help_works_now), onClick = onDismiss, muted = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HelpRow(icon: ImageVector, tint: Color, title: String, body: String?) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        HelpText(title, body)
    }
}

@Composable
private fun HelpStep(number: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(22.dp).border(1.5.dp, Palette.textTertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelSmall, color = Palette.textSecondary)
        }
        HelpText(title, body)
    }
}

@Composable
private fun HelpText(title: String, body: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.textPrimary)
        if (body != null) {
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.textSecondary)
        }
    }
}

/** Quiet text link with a 44dp touch target and TV focus glow. */
@Composable
internal fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Palette.accent,
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .focusGlow(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
}
