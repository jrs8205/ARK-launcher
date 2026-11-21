package org.arkikeskus.launcher.feature.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.LastBackup
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.backup.BackupRepository
import javax.inject.Inject

sealed interface BackupEvent {
    /** The file [name] is written. [profile] is the stored name of the home profile it is of (empty for one the
     *  user never named) when there are several profiles; null with one, where there is nothing to tell apart. */
    data class Exported(val name: String, val profile: String? = null) : BackupEvent
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

    /** The home profile that is on was no longer the one the export's button named when the file was to be
     *  written: nothing was exported, and exporting again writes the profile that is on. */
    data object ExportProfileChanged : BackupEvent
    data class Failed(val message: String) : BackupEvent

    /** A message too long to read in a short snackbar: it stays until dismissed or timed out long. */
    val needsTimeToRead: Boolean
        get() = this is ReadBlocked || this is ProfilesChanged || this is ExportProfileChanged ||
            (this is Restored && profilesLeft > 0)
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

    /** When the home profile that is on was last exported to a file, WITH that profile (one value: the
     *  screen shows the time only beside the profile it is of); null until it has been read. */
    val lastBackup: StateFlow<LastBackup?> =
        settings.localLastBackup.stateIn<LastBackup?>(viewModelScope, SharingStarted.Eagerly, null)

    private val appVersion: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

    /**
     * Writes the home profile [shownProfile] to [uri]: the one the export's button named when it was tapped.
     * With another profile on by now (the system's file picker was open meanwhile) nothing is written and the
     * screen says so; the picked document is left as it was ([exportToDocument]). The result names the profile
     * the file is of when there are several.
     */
    fun exportTo(uri: Uri, shownProfile: Long) = viewModelScope.launch {
        _events.emit(
            exportToDocument(context.contentResolver, uri) { write ->
                // The document is the export's place: two exports to one file go one after the other.
                backupRepository.export(System.currentTimeMillis(), appVersion, expectedProfile = shownProfile, target = uri, write = write)
            },
        )
    }

    /** Restores [uri], held to the profiles its question showed ([asked]). */
    fun importFrom(uri: Uri, asked: RestoreProfiles) = viewModelScope.launch {
        _events.emit(restorer.restoreFrom(uri, asked.confirmed) {})
    }

    internal companion object {
        const val TAG = "BackupVM"
    }
}
