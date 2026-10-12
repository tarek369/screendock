package com.cressu.screendock.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches local (Wi-Fi / Ethernet) networks and reports when the usable address set changes so
 * mDNS can be re-advertised. Events are debounced; the very first signature observed after
 * [start] is treated as baseline (no callback), so registering never causes a spurious re-announce.
 */
class NetworkWatcher(
    context: Context,
    /** [hasNetwork] false = every local network was lost. Called on the main thread. */
    private val onChanged: (hasNetwork: Boolean) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val addresses = HashMap<Network, String>() // guarded by main thread via post
    private var lastSignature: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val fire = Runnable {
        val sig = signature(addresses.values)
        val prev = lastSignature
        lastSignature = sig
        if (prev != null && prev != sig) onChanged(addresses.isNotEmpty())
    }

    fun start() {
        if (callback != null) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                main.post {
                    addresses[network] = lp.linkAddresses.joinToString(",") { it.address.hostAddress.orEmpty() }
                    schedule()
                }
            }
            override fun onLost(network: Network) {
                main.post { addresses.remove(network); schedule() }
            }
        }
        try {
            cm.registerNetworkCallback(req, cb)
            callback = cb
        } catch (t: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed", t)
        }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        main.removeCallbacksAndMessages(null)
        addresses.clear()
        lastSignature = null
    }

    private fun schedule() {
        main.removeCallbacks(fire)
        main.postDelayed(fire, DEBOUNCE_MS)
    }

    companion object {
        private const val TAG = "AirPlayNet"
        private const val DEBOUNCE_MS = 1_500L

        /** Order-independent signature of the address set. */
        internal fun signature(values: Collection<String>): String =
            values.flatMap { it.split(',') }.filter { it.isNotBlank() }.sorted().joinToString("|")
    }
}
