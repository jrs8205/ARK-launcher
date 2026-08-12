package org.arkikeskus.launcher.feature.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupFormatException
import org.arkikeskus.launcher.data.backup.BackupRepository
import org.json.JSONException
import javax.inject.Inject

sealed interface BackupEvent {
    data class Exported(val name: String) : BackupEvent
    data class Restored(val restored: Int, val skipped: Int) : BackupEvent
    data object InvalidFile : BackupEvent
    data object TooLarge : BackupEvent
    data class Failed(val message: String) : BackupEvent
}

/** Thrown when a backup file exceeds the import size cap — never buffer it whole. */
internal class BackupTooLargeException : Exception()

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val appRepository: AppRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** Epoch-ms timestamp of the last successful local file export (0 = never), for the file card. */
    private val _localLastBackupMs = MutableStateFlow(0L)
    val localLastBackupMs: StateFlow<Long> = _localLastBackupMs.asStateFlow()

    init {
        viewModelScope.launch {
            settings.localLastBackupTime.collect { time -> _localLastBackupMs.value = time }
        }
    }

    private val appVersion: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

    fun exportTo(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val doc = backupRepository.exportDocument(createdAt = System.currentTimeMillis(), appVersion = appVersion)
                context.contentResolver.openOutputStream(uri)?.use { it.write(BackupCodec.encode(doc).toByteArray()) }
                    ?: error("Could not open output stream")
            }
        }.onSuccess {
            settings.setLocalLastBackup(System.currentTimeMillis())
            _events.emit(BackupEvent.Exported(uri.lastPathSegment ?: ""))
        }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "File export failed", it)
                // Blank message → the screen shows its localized generic failure text.
                _events.emit(BackupEvent.Failed(it.message.orEmpty()))
            }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val json = context.contentResolver.openInputStream(uri)?.use { readBounded(it).decodeToString() }
                    ?: error("Could not open input stream")
                val doc = BackupCodec.decode(json)
                val apps = installedApps()
                backupRepository.restoreDocument(
                    doc = doc,
                    installedAppKeys = apps.map { "${it.packageName}/${it.className}" }.toSet(),
                    installedPackages = apps.map { it.packageName }.toSet(),
                )
            }
        }.onSuccess { _events.emit(BackupEvent.Restored(it.restored, it.skipped)) }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                when {
                    it is BackupTooLargeException -> _events.emit(BackupEvent.TooLarge)
                    it is BackupFormatException || it is JSONException -> _events.emit(BackupEvent.InvalidFile)
                    else -> _events.emit(BackupEvent.Failed(it.message.orEmpty()))
                }
            }
    }

    /** The current app list, REQUIRED non-empty: a transient LauncherApps failure yields an empty
     *  list, and restoring against it would silently drop every app item from the layout. */
    private suspend fun installedApps() =
        kotlinx.coroutines.withTimeoutOrNull(APP_LIST_TIMEOUT_MS) {
            appRepository.apps.first { it.isNotEmpty() }
        } ?: error("App list unavailable")

    /** Reads the stream fully but never past [MAX_BACKUP_BYTES] — an oversized or corrupt file
     *  must fail fast instead of OOMing the HOME process. */
    private fun readBounded(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BACKUP_BYTES) throw BackupTooLargeException()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    internal companion object {
        const val TAG = "BackupVM"

        /** Hard cap for a backup file; a real backup is a few kB, so 10 MiB is generous. */
        const val MAX_BACKUP_BYTES = 10L * 1024 * 1024

        private const val APP_LIST_TIMEOUT_MS = 10_000L
    }
}
