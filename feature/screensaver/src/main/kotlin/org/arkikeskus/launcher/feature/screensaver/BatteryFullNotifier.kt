package org.arkikeskus.launcher.feature.screensaver

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers that a stretch of full battery was already told about. */
internal class FullChargeMark {
    private var told = false

    /** True exactly once per stretch of full: at its first sighting. A battery that is not full ends the stretch. */
    @Synchronized
    fun onState(full: Boolean): Boolean {
        if (!full) {
            told = false
            return false
        }
        if (told) return false
        told = true
        return true
    }

    @Synchronized
    fun clear() {
        told = false
    }
}

/**
 * The notification when the battery is full while the screensaver is up. It is the one thing the launcher
 * sends a notification for, and it stays behind its own switch (Settings, the Screensaver page), which is
 * where the permission is asked for.
 *
 * One notification per full charge: the mark outlives a run of the screensaver, so a phone that rests at
 * 100 % is not told again each time the screensaver restarts. The stretch ends when the screensaver sees
 * the battery leave full, or when the charger is taken away.
 */
@Singleton
class BatteryFullNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val mark = FullChargeMark()

    /** The screensaver's word on the battery, at every battery broadcast while it is up. */
    fun onChargeState(full: Boolean) {
        if (!full) {
            mark.clear()
            return
        }
        // Only a notification that can really go out uses the mark up: switched on later, or allowed
        // later, a battery that is still full is told then.
        if (!enabled() || !canPost()) return
        if (mark.onState(full = true)) post()
    }

    /** The charger was taken away: whatever comes next is another charge. */
    fun onPowerDisconnected() = mark.clear()

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
