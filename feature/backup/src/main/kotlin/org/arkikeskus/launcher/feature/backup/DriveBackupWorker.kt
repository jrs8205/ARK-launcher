package org.arkikeskus.launcher.feature.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupRepository
import org.arkikeskus.launcher.feature.backup.drive.DriveRestClient
import org.arkikeskus.launcher.feature.backup.drive.TokenProvider
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Reads [PackageInfo.versionName], defaulting to "?" on any error. */
internal fun appVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

/**
 * Periodic WorkManager worker that uploads a Drive backup if the content has changed since the
 * last upload (dedup via SHA-256 hash over the normalised JSON).
 *
 * Bail-out conditions (silent success):
 *   - Drive backup is disabled in settings.
 *
 * Retry conditions:
 *   - Silent token unavailable (consent UI required → no token).
 *   - Any network or serialisation error during upload.
 */
@HiltWorker
class DriveBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupRepository: BackupRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Bail if the user has not enabled Drive backup.
        if (!settings.driveEnabledOnce()) return@withContext Result.success()

        // Obtain a silent token — if consent UI is required we cannot proceed from a worker.
        val token = TokenProvider.silentToken(applicationContext)
            ?: return@withContext Result.retry()

        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        runCatching {
            val doc = backupRepository.exportDocument(
                createdAt = System.currentTimeMillis(),
                appVersion = appVersion(applicationContext),
            )
            // Dedup: hash over content with createdAt zeroed so identical layouts on different
            // days produce the same hash.
            val hashable = BackupCodec.encode(doc.copy(createdAt = 0L))
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(hashable.toByteArray())
                .joinToString("") { "%02x".format(it) }

            if (hash == settings.driveLastHash()) return@runCatching // already up-to-date

            val client = DriveRestClient(token, http)
            client.upload(
                "arkikeskus-launcher-backup-${System.currentTimeMillis()}.json",
                BackupCodec.encode(doc),
            )
            client.pruneToNewest(5)
            settings.setDriveLastBackup(System.currentTimeMillis(), hash)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { t ->
                if (t is CancellationException) throw t
                Result.retry()
            },
        )
    }
}
