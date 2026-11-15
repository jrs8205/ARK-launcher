package org.arkikeskus.launcher.feature.screensaver

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import org.arkikeskus.launcher.feature.screensaver.dream.DreamChargeState
import org.arkikeskus.launcher.feature.screensaver.dream.dreamChargeState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers that a stretch of full battery was already told about. The mark is kept in [prefs], the file of
 * this phone's own facts: Android may restart the launcher's process while the phone rests at 100 %, and a
 * mark held only in memory would tell the same full charge again.
 */
internal class FullChargeMark(private val prefs: SharedPreferences) {
    val isSet: Boolean get() = prefs.getBoolean(DeviceLocalFlags.KEY_BATTERY_FULL_TOLD, false)

    /** True exactly once per stretch of full: at its first sighting. A battery that is not full ends the stretch. */
    @Synchronized
    fun onState(full: Boolean): Boolean {
        if (!full) {
            clear()
            return false
        }
        if (isSet) return false
        prefs.edit { putBoolean(DeviceLocalFlags.KEY_BATTERY_FULL_TOLD, true) }
        return true
    }

    @Synchronized
    fun clear() {
        if (isSet) prefs.edit { remove(DeviceLocalFlags.KEY_BATTERY_FULL_TOLD) }
    }
}

/**
 * The notification when the battery is full while the screensaver is up. It is the one thing the launcher
 * sends a notification for, and it stays behind its own switch (Settings, the Screensaver page), which is
 * where the permission is asked for.
 *
 * One notification per full charge: the mark outlives a run of the screensaver and the launcher's process,
 * so a phone that rests at 100 % is not told again each time the screensaver, or the launcher, restarts.
 * The stretch ends when the battery leaves full or the charger is taken away, whether the screensaver is
 * up or not: while a full charge is marked, and only then, the battery is listened to here as well. A phone
 * used on its charger that drops to 99 % and fills again is then told at the screensaver's next start.
 * Telling stays the screensaver's: this listening only ever ends a stretch.
 */
@Singleton
class BatteryFullNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val mark = FullChargeMark(DeviceLocalFlags.prefs(context))

    private var watching = false
    private val batteryWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBatteryWhileMarked(intent)
    }

    /** The screensaver's word on the battery, at every battery broadcast while it is up. */
    fun onChargeState(full: Boolean) {
        if (!full) {
            endStretch()
            return
        }
        // Only a notification that can really go out uses the mark up: switched on later, or allowed
        // later, a battery that is still full is told then.
        if (!enabled() || !canPost()) return
        if (mark.onState(full = true)) {
            post()
            syncWatch()
        }
    }

    /** The charger was taken away: whatever comes next is another charge. */
    fun onPowerDisconnected() = endStretch()

    /**
     * Called once when the launcher's process starts: a full charge the previous process marked is listened
     * to again. The battery's state right now arrives with the registration, so a battery that left full,
     * or lost its charger, while no process was there to see it ends the stretch at once.
     */
    fun resumeWatch() = syncWatch()

    private fun endStretch() {
        mark.clear()
        syncWatch()
    }

    /** Listens to the battery exactly while a full charge is marked. */
    @Synchronized
    private fun syncWatch() {
        val wanted = mark.isSet
        if (wanted == watching) return
        watching = wanted
        if (wanted) {
            // The battery broadcast is sticky: registering returns the state as it is now. It is read
            // here instead of waiting for its redelivery, which some phones leave out.
            context.registerReceiver(batteryWatch, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::onBatteryWhileMarked)
        } else {
            context.unregisterReceiver(batteryWatch)
        }
    }

    /** Ends the stretch when the battery is no longer full on its charger; a full one changes nothing. */
    private fun onBatteryWhileMarked(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        // The same reading of the broadcast as the screensaver's own (DreamStateCollector).
        val state = dreamChargeState(
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            level = level * 100 / scale,
        )
        if (state != DreamChargeState.FULL) endStretch()
    }

    private fun enabled(): Boolean =
        context.screensaverPreferences().getBoolean(DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION, false)

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in canPost(); a revoke in between is caught below
    private fun post() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.battery_full_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.battery_full_channel_description) },
        )
        // A tap leads to the launcher's own home screen, whichever app is the default home.
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_battery)
            .setContentTitle(context.getString(R.string.battery_full_notification_title))
            .setContentText(context.getString(R.string.battery_full_notification_text))
            .setContentIntent(
                PendingIntent.getActivity(context, 0, home, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission can be taken away between the check and the post.
        }
    }

    private companion object {
        const val CHANNEL_ID = "battery_full"
        const val NOTIFICATION_ID = 1002
    }
}
