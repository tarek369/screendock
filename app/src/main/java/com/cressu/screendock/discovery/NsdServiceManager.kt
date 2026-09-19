package com.cressu.screendock.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Advertises the AirPlay receiver over mDNS using Android's [NsdManager].
 *
 * UxPlay's C core is unaware of Android's networking stack — it builds the
 * TXT records and the RAOP instance label, and this class is responsible for
 * getting them on the wire via the platform NSD service.
 *
 * Two services are registered:
 *  - `_airplay._tcp` whose NSD service-name is the bare display name
 *    (e.g. `"LivingRoom"`).
 *  - `_raop._tcp` whose NSD service-name is the `"<hexmac>@<name>"` label
 *    returned by `NativeBridge.getRaopServiceName(handle)`, with any
 *    trailing `._raop._tcp.local` suffix stripped (NsdManager wants the
 *    bare instance label, not the fully-qualified domain).
 *
 * **Multicast lock**: a non-reference-counted [WifiManager.MulticastLock]
 * is held for the lifetime of a registration. Without it Android drops
 * inbound mDNS multicast packets while dozing and the receiver never
 * appears in the sender's AirPlay picker.
 *
 * **Thread-safety**: every public method may be called from any thread.
 * A single intrinsic monitor ([lock]) guards registration state; the
 * running flag is additionally `@Volatile` so [isRunning] can be polled
 * without taking the lock.
 */
class NsdServiceManager(private val context: Context) {

    /**
     * Begin advertising both services.
     *
     * @param displayName      Human-readable receiver name, used verbatim as
     *                         the `_airplay._tcp` instance label.
     * @param port             TCP port the native httpd bound to.
     * @param txtAirplay       TXT attribute map for `_airplay._tcp`
     *                         (keys already lowercase).
     * @param txtRaop          TXT attribute map for `_raop._tcp`
     *                         (keys already lowercase).
     * @param raopInstanceName The `"<hexmac>@<name>"` label produced by the
     *                         native core. A trailing `._raop._tcp.local`
     *                         suffix is tolerated and stripped.
     */
    fun start(
        displayName: String,
        port: Int,
        txtAirplay: Map<String, String>,
        txtRaop: Map<String, String>,
        raopInstanceName: String,
    ) {
        synchronized(lock) {
            if (isRunning) {
                Log.w(TAG, "start(): already running, ignoring duplicate start.")
                return
            }
            acquireMulticastLock()
            try {
                registerAirplayLocked(displayName, port, txtAirplay)
                registerRaopLocked(raopInstanceName, port, txtRaop)
                running = true
                Log.i(
                    TAG,
                    "Started: airplay='$displayName' raop='${stripRaopSuffix(raopInstanceName)}' port=$port"
                )
            } catch (t: Throwable) {
                // Roll back anything that did register so we leave a clean slate.
                Log.e(TAG, "start() failed; rolling back registration.", t)
                tearDownListenersLocked()
                releaseMulticastLock()
                running = false
                throw t
            }
        }
    }

    /**
     * Tear down both registrations and release the multicast lock.
     *
     * Safe to call multiple times: a no-op when nothing is registered.
     */
    fun stop() {
        synchronized(lock) {
            if (!running && listeners.isEmpty()) return
            Log.i(TAG, "Stopping NSD advertisement.")
            tearDownListenersLocked()
            releaseMulticastLock()
            running = false
        }
    }

    /**
     * Re-register with a new display name. Called from settings when the
     * user renames the receiver. Equivalent to [stop] followed by [start];
     * there is intentionally no in-place rename path because NsdManager
     * cannot mutate a live registration.
     */
    fun updateDisplayName(
        displayName: String,
        txtAirplay: Map<String, String>,
        txtRaop: Map<String, String>,
        raopInstanceName: String,
        port: Int,
    ) {
        stop()
        start(displayName, port, txtAirplay, txtRaop, raopInstanceName)
    }

    /** `true` while both services are believed to be registered. */
    val isRunning: Boolean
        get() = running

    // ---- internals --------------------------------------------------------

    private val lock = Any()

    @Volatile
    private var running: Boolean = false

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Live registration listeners. Held so [stop] can unregister them.
     * Mutated only while holding [lock].
     */
    private val listeners = ArrayList<NsdManager.RegistrationListener>()

    private fun registerAirplayLocked(
        displayName: String,
        port: Int,
        txt: Map<String, String>,
    ) {
        val info = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE_AIRPLAY
            setPort(port)
            applyTxt(txt)
        }
        registerServiceLocked(info, "airplay")
    }

    private fun registerRaopLocked(
        raopInstanceName: String,
        port: Int,
        txt: Map<String, String>,
    ) {
        val bareInstance = stripRaopSuffix(raopInstanceName)
        val info = NsdServiceInfo().apply {
            serviceName = bareInstance
            serviceType = SERVICE_TYPE_RAOP
            setPort(port)
            applyTxt(txt)
        }
        registerServiceLocked(info, "raop")
    }

    /** Caller must hold [lock]. Adds the listener before registering so a
     *  synchronous failure path can still find it for cleanup. */
    private fun registerServiceLocked(
        info: NsdServiceInfo,
        label: String,
    ) {
        val listener = makeListener(label)
        listeners.add(listener)
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            listeners.remove(listener)
            Log.e(TAG, "registerService threw for '${info.serviceName}' ($label).", t)
            throw t
        }
    }

    /** Caller must hold [lock]. Snapshots first to avoid CME if the platform
     *  fires callbacks synchronously during unregister. */
    private fun tearDownListenersLocked() {
        val snapshot = ArrayList(listeners)
        listeners.clear()
        for (l in snapshot) {
            try {
                nsdManager.unregisterService(l)
            } catch (t: Throwable) {
                // Already tearing down — never crash, just log.
                Log.w(TAG, "unregisterService failed.", t)
            }
        }
    }

    private fun NsdServiceInfo.applyTxt(txt: Map<String, String>) {
        for ((key, value) in txt) {
            if (key.isBlank()) continue
            // NsdManager lowercases keys and rejects blanks; values are
            // length-prefixed in the on-wire TXT record so they must fit
            // in a single byte (<=255 chars).
            setAttribute(key, truncate(value))
        }
    }

    private fun makeListener(label: String): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "[$label] registered as '${info.serviceName}'.")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // Do not crash — the service layer may still limp along on
                // the other registration, and the user can retry.
                Log.e(
                    TAG,
                    "[$label] registration FAILED (errorCode=$errorCode) " +
                        "for '${info.serviceName}'."
                )
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "[$label] unregistered '${info.serviceName}'.")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(
                    TAG,
                    "[$label] unregistration FAILED (errorCode=$errorCode) " +
                        "for '${info.serviceName}'."
                )
            }
        }

    // ---- multicast lock ---------------------------------------------------

    private fun acquireMulticastLock() {
        val existing = multicastLock
        if (existing != null && existing.isHeld) return
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val l = wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = l
        Log.i(TAG, "MulticastLock acquired (refCounted=false).")
    }

    private fun releaseMulticastLock() {
        val l = multicastLock ?: return
        try {
            if (l.isHeld) l.release()
        } catch (t: Throwable) {
            Log.w(TAG, "MulticastLock.release() failed.", t)
        }
        multicastLock = null
    }

    // ---- helpers ----------------------------------------------------------

    /** Strip a trailing `._raop._tcp.local` (with or without a final dot)
     *  so NsdManager receives only the `<hexmac>@<name>` instance label. */
    private fun stripRaopSuffix(name: String): String {
        var s = name.trim()
        if (s.endsWith("$SUFFIX_RAOP_LOCAL.")) {
            s = s.dropLast(SUFFIX_RAOP_LOCAL.length + 1)
        } else if (s.endsWith(SUFFIX_RAOP_LOCAL)) {
            s = s.dropLast(SUFFIX_RAOP_LOCAL.length)
        }
        return s
    }

    private fun truncate(value: String, max: Int = MAX_ATTR_VALUE_LEN): String =
        if (value.length > max) value.substring(0, max) else value

    private companion object {
        const val TAG = "AirplayNsd"

        // Trailing dot: the canonical fully-qualified service type form that
        // UxPlay/Bonjour use and that iOS senders probe for. NsdManager
        // accepts both forms; the dotted form is interoperable.
        const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp."
        const val SERVICE_TYPE_RAOP = "_raop._tcp."

        const val SUFFIX_RAOP_LOCAL = "._raop._tcp.local"
        const val MULTICAST_LOCK_TAG = "airplay_mdns"

        // DNS TXT values are length-prefixed by a single byte on the wire.
        const val MAX_ATTR_VALUE_LEN = 255
    }
}
