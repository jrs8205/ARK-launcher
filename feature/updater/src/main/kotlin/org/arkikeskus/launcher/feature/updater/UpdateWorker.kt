package org.arkikeskus.launcher.feature.updater

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.arkikeskus.launcher.data.SettingsRepository

@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: UpdateRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!isReleaseBuild(applicationContext)) return Result.success()
        if (!settings.autoUpdateEnabledOnce()) return Result.success()
        val current = runCatching {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
        }.getOrNull() ?: return Result.success()
        return runCatching {
            val info = repository.checkLatest(current)
            settings.setUpdateLastCheck(System.currentTimeMillis())
            if (info != null && info.versionName != settings.updateLastNotifiedVersion()) {
                UpdateNotifier.notifyAvailable(applicationContext, info)
                settings.setUpdateLastNotifiedVersion(info.versionName)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Result.retry()
            },
        )
    }
}
