package org.arkikeskus.launcher.launcher.system

import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.arkikeskus.launcher.model.SystemFlags
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams status-bar flags: airplane, silent/vibrate, bluetooth, alarm, location, NFC, VPN,
 * data saver and Do-Not-Disturb. Each is read best-effort (try/catch) so a missing service or
 * permission just yields false rather than crashing.
 */
@Singleton
class SystemFlagsMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    val flags: Flow<SystemFlags> = callbackFlow {
        fun emit() = trySend(current()).let { }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = emit()
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, observer,
        )
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor("zen_mode"), false, observer,
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = emit()
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        emit()
        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    private fun current(): SystemFlags {
        val ringer = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        return SystemFlags(
            airplane = readBool { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 },
            silent = ringer == AudioManager.RINGER_MODE_SILENT,
            vibrate = ringer == AudioManager.RINGER_MODE_VIBRATE,
            bluetooth = readBool { bluetoothManager?.adapter?.isEnabled == true },
            alarm = readBool { alarmManager?.nextAlarmClock != null },
            location = readBool { locationManager?.isLocationEnabled == true },
            nfc = readBool { NfcAdapter.getDefaultAdapter(context)?.isEnabled == true },
            vpn = readBool { hasVpn() },
            dataSaver = readBool {
                connectivityManager?.restrictBackgroundStatus ==
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            },
            dnd = readBool {
                (notificationManager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) !=
                    NotificationManager.INTERRUPTION_FILTER_ALL
            },
        )
    }

    private fun hasVpn(): Boolean {
        val cm = connectivityManager ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private inline fun readBool(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: Exception) {
        false
    }
}
