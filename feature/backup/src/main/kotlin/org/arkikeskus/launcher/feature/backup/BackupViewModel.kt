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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupRepository
import javax.inject.Inject

sealed interface BackupEvent {
    data class Exported(val name: String) : BackupEvent
    data class Restored(val restored: Int, val skipped: Int) : BackupEvent
    data object InvalidFile : BackupEvent
    data object TooLarge : BackupEvent

    /** The system would not let the launcher read the picked file ([BackupReadBlockedException]). */
    data object ReadBlocked : BackupEvent
    data class Failed(val message: String) : BackupEvent

    /** A message too long to read in a short snackbar: it stays until dismissed or timed out long. */
    val needsTimeToRead: Boolean get() = this is ReadBlocked
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val restorer: BackupRestorer,
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
        _events.emit(restorer.restoreFrom(uri))
    }

    internal companion object {
        const val TAG = "BackupVM"
    }
}
