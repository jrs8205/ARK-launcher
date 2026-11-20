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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
    /** The backup is in. [profilesLeft] other home profiles are still listed, empty: their rows went with the
     *  restore, and the list could not be written after it (`RestoreResult.profilesLeft`). */
    data class Restored(val restored: Int, val skipped: Int, val profilesLeft: Int = 0) : BackupEvent
    data object InvalidFile : BackupEvent
    data object TooLarge : BackupEvent

    /** The system would not let the launcher read the picked file ([BackupReadBlockedException]). */
    data object ReadBlocked : BackupEvent

    /** The picked file did not arrive in time ([BackupReadTimeoutException]). */
    data object ReadTimedOut : BackupEvent

    /** The home profiles were no longer the ones the question named when the file had been read: nothing
     *  was restored, and importing again asks with the profiles there are. */
    data object ProfilesChanged : BackupEvent
    data class Failed(val message: String) : BackupEvent

    /** A message too long to read in a short snackbar: it stays until dismissed or timed out long. */
    val needsTimeToRead: Boolean
        get() = this is ReadBlocked || this is ProfilesChanged || (this is Restored && profilesLeft > 0)
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val restorer: BackupRestorer,
    private val settings: SettingsRepository,
    lost: ProfilesLostInRestore,
) : ViewModel() {

    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** The home profiles a restore would meet now, for its question; null until they have been read. */
    val restoreProfiles: StateFlow<RestoreProfiles?> =
        lost.profiles().stateIn<RestoreProfiles?>(viewModelScope, SharingStarted.Eagerly, null)

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
                context.contentResolver.writeDocument(uri, BackupCodec.encode(doc).toByteArray())
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

    /** Restores [uri], held to the profiles its question showed ([asked]). */
    fun importFrom(uri: Uri, asked: RestoreProfiles) = viewModelScope.launch {
        _events.emit(restorer.restoreFrom(uri, asked.confirmed) {})
    }

    internal companion object {
        const val TAG = "BackupVM"
    }
}
