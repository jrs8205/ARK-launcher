package org.arkikeskus.launcher.launcher.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.arkikeskus.launcher.model.WifiBand
import org.arkikeskus.launcher.model.WifiStatus
import javax.inject.Inject
import javax.inject.Singleton

/** Streams Wi-Fi connection state + signal level + band via a Wi-Fi network callback. */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    private val disconnected = WifiStatus(connected = false, level = 0, band = WifiBand.UNKNOWN)

    val wifi: Flow<WifiStatus> = callbackFlow {
        // The callback can track SEVERAL Wi-Fi networks at once (STA+STA concurrency, make-before-break
        // handovers); each gets its own onLost. Keep a per-network map and only report disconnected
        // when the LAST one is gone — otherwise one network's loss falsely blanked the indicator.
        val networks = HashMap<Network, WifiStatus>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                networks[network] = wifiStatusOf(caps)
                emitCurrent()
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                emitCurrent()
            }

            override fun onUnavailable() {
                networks.clear()
                trySend(disconnected)
            }

            private fun emitCurrent() {
                val best = networks.values.filter { it.connected }.maxByOrNull { it.level }
                trySend(best ?: disconnected)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        // The seed first: the callback reports on another thread, and a seed sent after registering
        // could land on top of a real first reading and blank the icon until the next change.
        trySend(disconnected)
        connectivityManager?.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager?.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** The status a Wi-Fi network's capabilities describe. On Android 11 they describe nothing of the
     *  link (WifiInfo is a TransportInfo only from 12), so there the Wi-Fi manager's own connection is
     *  read: level 0 — the red end of the ramp — and no band is what every Android 11 phone got. */
    internal fun wifiStatusOf(caps: NetworkCapabilities): WifiStatus {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return disconnected
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            caps.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION") // deprecated only from 12, where the capabilities carry it
            wifiManager?.connectionInfo
        }
        val rssi = info?.rssi ?: -127
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wifiManager?.calculateSignalLevel(rssi) ?: 0
        } else {
            // The five-step formula: the instance method of 11 asks the Wi-Fi service, which a unit
            // test cannot drive, and the formula is what 11 computes anyway.
            @Suppress("DEPRECATION")
            WifiManager.calculateSignalLevel(rssi, 5)
        }.coerceIn(0, 4)
        val band = when (info?.frequency ?: 0) {
            in 2401..2499 -> WifiBand.GHZ_2_4
            in 4900..5899 -> WifiBand.GHZ_5
            in 5900..7125 -> WifiBand.GHZ_6
            else -> WifiBand.UNKNOWN
        }
        return WifiStatus(connected = true, level = level, band = band)
    }
}
