package com.cressu.screendock.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cressu.screendock.ui.components.dpadScrollable
import com.cressu.screendock.R
import com.cressu.screendock.ui.components.AuroraBackground
import com.cressu.screendock.ui.components.GlassIconButton
import com.cressu.screendock.ui.components.SettingsCard
import com.cressu.screendock.ui.theme.Palette
import java.net.Inet4Address
import kotlinx.coroutines.delay

/** What the user needs to compare against the sender's network. */
data class NetworkSummary(
    val ipAddress: String?,
    val transport: Transport,
    val networkName: String?,
    val vpnOn: Boolean = false,
    /** Wi-Fi band in GHz (2.4 / 5 / 6), null when unknown or not on Wi-Fi. */
    val wifiBandGhz: String? = null,
) {
    enum class Transport { WIFI, ETHERNET, OTHER, NONE }
}

object NetworkInfoProvider {
    @SuppressLint("MissingPermission", "NewApi")
    @Suppress("DEPRECATION")
    fun read(context: Context): NetworkSummary {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkSummary(null, NetworkSummary.Transport.NONE, null)
        val active = cm.activeNetwork
            ?: return NetworkSummary(null, NetworkSummary.Transport.NONE, null)
        val vpnOn = cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        // Behind a VPN the active network is the tunnel; describe the physical link instead.
        val network = if (!vpnOn) active else cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } == true
        } ?: active
        val caps = cm.getNetworkCapabilities(network)
        val transport = when {
            caps == null -> NetworkSummary.Transport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkSummary.Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkSummary.Transport.ETHERNET
            else -> NetworkSummary.Transport.OTHER
        }
        val ip = cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
        // SSID is only readable with location permission on newer Android; show it when
        // the OS gives it to us and fall back to the transport label otherwise.
        val wifiInfo = if (transport == NetworkSummary.Transport.WIFI) {
            runCatching {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
            }.getOrNull()
        } else null
        val ssid = wifiInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val band = wifiInfo?.let { WifiBand.fromMhz(it.frequency) }
        return NetworkSummary(ip, transport, ssid, vpnOn, band)
    }
}

@Composable
fun TroubleshootingScreen(
    deviceName: String,
    receiverRunning: Boolean,
    diagnostics: (NetworkSummary) -> SupportDiagnostics,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var net by remember { mutableStateOf(NetworkInfoProvider.read(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            net = NetworkInfoProvider.read(context)
            delay(3_000L)
        }
    }
    val backFocus = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    LaunchedEffect(isTv) {
        if (isTv) { delay(150); runCatching { backFocus.requestFocus() } }
    }

    val transportLabel = when (net.transport) {
        NetworkSummary.Transport.WIFI -> stringResource(R.string.ts_transport_wifi)
        NetworkSummary.Transport.ETHERNET -> stringResource(R.string.ts_transport_ethernet)
        NetworkSummary.Transport.OTHER -> stringResource(R.string.ts_transport_other)
        NetworkSummary.Transport.NONE -> stringResource(R.string.ts_transport_none)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())
        val scroll = rememberScrollState()
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().dpadScrollable(scroll).verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = 640.dp).padding(horizontal = 24.dp, vertical = 20.dp),
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
                        Text(
                            stringResource(R.string.ts_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = Palette.textPrimary,
                        )
                    }

                    SettingsCard(title = stringResource(R.string.ts_card_network), icon = Icons.Rounded.Wifi) {
                        InfoRow(stringResource(R.string.ts_row_name), deviceName)
                        InfoRow(stringResource(R.string.ts_row_ip), net.ipAddress ?: stringResource(R.string.ts_ip_none))
                        InfoRow(
                            stringResource(R.string.ts_row_network),
                            listOfNotNull(transportLabel, net.networkName).joinToString(" · "),
                        )
                        InfoRow(
                            stringResource(R.string.ts_row_receiver),
                            stringResource(if (receiverRunning) R.string.ts_receiver_on else R.string.ts_receiver_off),
                        )
                    }

                    TellUsCard(diagnostics = { diagnostics(net) })

                    SettingsCard(title = stringResource(R.string.ts_card_tips), icon = Icons.Rounded.Lightbulb) {
                        listOf(
                            R.string.ts_tip_same_network,
                            R.string.ts_tip_5ghz,
                            R.string.ts_tip_guest_wifi,
                            R.string.ts_tip_battery,
                            R.string.ts_tip_vpn,
                            R.string.ts_tip_restart,
                        ).forEach { res ->
                            Text(
                                "•  " + stringResource(res),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.textSecondary,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.textTertiary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Palette.textPrimary)
    }
}
