package org.arkikeskus.launcher.feature.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts the "Drive backup keeps failing" notification. [DriveBackupWorker] fires this once per
 * failing episode (when the consecutive-failure counter reaches the threshold); a successful
 * backup resets the counter so a later episode notifies again.
 */
object DriveBackupNotifier {
    private const val CHANNEL = "backup"
    private const val NOTIF_ID = 4301

    fun notifyFailing(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.backup_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // Tapping opens SettingsActivity (which hosts the backup screen).
        // Use setClassName so feature:backup doesn't need a compile dep on :app.
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
            .setSmallIcon(R.drawable.ic_backup_notification)
            .setContentTitle(context.getString(R.string.backup_drive_failing_title))
            .setContentText(context.getString(R.string.backup_drive_failing_text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // Guard: POST_NOTIFICATIONS not granted on API 33+ silently skips the push;
        // the warning banner on the backup screen covers that case.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notif) }
    }
}
