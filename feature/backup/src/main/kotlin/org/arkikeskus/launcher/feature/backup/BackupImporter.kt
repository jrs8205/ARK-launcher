package org.arkikeskus.launcher.feature.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupRepository
import javax.inject.Inject

/** Restores the home layout and settings from a backup file: the backup screen's import and the
 *  "Open with" import both go through it, so both have the same checks. */
fun interface BackupRestorer {
    /**
     * [BackupEvent.Restored], or the failure as the screen shows it ([importFailureEvent]). Reading
     * and checking the file can be cancelled at any point and changes nothing; [onWriting] is called
     * when the layout starts being replaced, a short step that always finishes, cancelled or not.
     */
    suspend fun restoreFrom(uri: Uri, onWriting: () -> Unit): BackupEvent
}

internal class BackupImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val appRepository: AppRepository,
) : BackupRestorer {

    override suspend fun restoreFrom(uri: Uri, onWriting: () -> Unit): BackupEvent = runCatching {
        val json = readBackupTextWithin(context.contentResolver, uri)
        val doc = withContext(Dispatchers.Default) { BackupCodec.decode(json) }
        val apps = installedApps().map { it.key }
        onWriting()
        // restoreDocument writes under NonCancellable: once started, it finishes.
        withContext(Dispatchers.IO) { backupRepository.restoreDocument(doc = doc, installedApps = apps) }
    }.fold(
        onSuccess = { BackupEvent.Restored(it.restored, it.skipped) },
        onFailure = {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "File import failed", it)
            importFailureEvent(it)
        },
    )

    /** The current app list, REQUIRED non-empty: a transient LauncherApps failure yields an empty
     *  list, and restoring against it would silently drop every app item from the layout. */
    private suspend fun installedApps() =
        kotlinx.coroutines.withTimeoutOrNull(APP_LIST_TIMEOUT_MS) {
            appRepository.apps.first { it.isNotEmpty() }
        } ?: error("App list unavailable")

    private companion object {
        const val TAG = "BackupImport"
        const val APP_LIST_TIMEOUT_MS = 10_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BackupModule {
    @Binds abstract fun restorer(impl: BackupImporter): BackupRestorer
}
