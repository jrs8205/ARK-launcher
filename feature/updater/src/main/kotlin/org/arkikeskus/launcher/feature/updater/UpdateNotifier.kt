package org.arkikeskus.launcher.feature.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object UpdateNotifier {
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4201

    fun notifyAvailable(context: Context, info: UpdateInfo) {
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
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_available_title, info.versionName))
            .setContentText(context.getString(R.string.update_check_now))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // Guard: POST_NOTIFICATIONS not granted on API 33+ silently skips the push;
        // the in-app card still works regardless.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notif) }
    }
}
