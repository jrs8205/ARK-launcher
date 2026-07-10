package org.arkikeskus.launcher.feature.updater

import android.Manifest
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

object UpdateNotifier {
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4201

    /** Posts the "update available" notification. Returns true only if it was actually shown, so the
     *  caller doesn't record the version as notified when POST_NOTIFICATIONS is missing (API 33+) or
     *  the post throws — otherwise a later grant would never re-surface that version. */
    fun notifyAvailable(context: Context, info: UpdateInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_notification_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Tapping opens SettingsActivity (which hosts the updater section).
        // Use setClassName so feature:updater doesn't need a compile dep on :app.
        // context.packageName may have a .debug suffix; the class name is always bare.
        val launch = Intent().apply {
            setClassName(context.packageName, "org.arkikeskus.launcher.SettingsActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle(context.getString(R.string.update_available_title, info.versionName))
            .setContentText(context.getString(R.string.update_check_now))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notif); true }
            .getOrDefault(false)
    }
}
