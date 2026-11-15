package org.arkikeskus.launcher.feature.screensaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hears the charger being connected and taken away, for as long as the launcher's process lives.
 * Android does not deliver these two broadcasts to a receiver named in the manifest, so the
 * receiver is registered here, once, when the process starts. What each event leads to is decided
 * when it arrives: with the start on charge switched off, a connected charger changes nothing. A charger
 * taken away ends a full charge for the battery-full notification, and a full charge the previous process
 * had marked as told is taken up again (see [BatteryFullNotifier.resumeWatch]).
 */
@Singleton
class ScreensaverPowerEvents @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chargeStart: ChargeStart,
    private val batteryFull: BatteryFullNotifier,
) {
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> chargeStart.onPowerConnected()
                Intent.ACTION_POWER_DISCONNECTED -> batteryFull.onPowerDisconnected()
            }
        }
    }

    /** Starts listening; a second call changes nothing. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
        batteryFull.resumeWatch()
    }
}
