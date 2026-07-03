package org.arkikeskus.launcher.feature.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules / cancels the periodic [DriveBackupWorker] via WorkManager.
 *
 * Call [schedule] when the user enables Drive backup or changes an option, and [cancel] when they
 * disable it. WorkManager deduplicates by [WORK] name with the UPDATE policy, so changing the
 * interval/constraints reschedules the existing work in place.
 */
object BackupScheduler {
    private const val WORK = "drive-backup-daily"

    /** How many dated backup files to keep in Drive's appDataFolder (newest-first). A few restore
     *  points, not a growing pile — the dedup means a new file appears only on a real layout change. */
    const val KEEP_BACKUPS = 3

    /**
     * (Re)schedules the periodic backup. [intervalDays] is the repeat interval (1 = daily, 7 = weekly),
     * [wifiOnly] requires an unmetered network, [chargingOnly] requires the device be charging.
     */
    fun schedule(context: Context, intervalDays: Int, wifiOnly: Boolean, chargingOnly: Boolean) {
        val req = PeriodicWorkRequestBuilder<DriveBackupWorker>(
            intervalDays.toLong().coerceAtLeast(1), TimeUnit.DAYS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(chargingOnly)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
