package org.arkikeskus.launcher.feature.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
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
     *  restore, and the list could not be written after it (`RestoreResult.profilesLeft`). [picturesLeft]: of
     *  that many profiles that went, the picture behind the app drawer could not be removed from the disk
     *  (`RestoreResult.picturesLeft`). */
    data class Restored(
        val restored: Int,
        val skipped: Int,
        val profilesLeft: Int = 0,
        val picturesLeft: Int = 0,
        val settingsCleanupIncomplete: Boolean = false,
    ) : BackupEvent
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
            (this is Restored && (profilesLeft > 0 || picturesLeft > 0 || settingsCleanupIncomplete))
}

@HiltViewModel
class BackupViewModel internal constructor(
    lastExport: Flow<LastBackup>,
    private val export: suspend (Uri, Long) -> BackupEvent,
    restorer: BackupRestorer,
    lost: ProfilesLostInRestore,
) : ViewModel() {
    @Inject constructor(
        @ApplicationContext context: Context,
        backupRepository: BackupRepository,
        restorer: BackupRestorer,
        settings: SettingsRepository,
        lost: ProfilesLostInRestore,
    ) : this(settings.localLastBackup, { uri, shownProfile ->
        exportToDocument(context.contentResolver, uri) { write ->
            val appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
            backupRepository.export(System::currentTimeMillis, appVersion, expectedProfile = shownProfile, target = uri, write = write)
        }
    }, restorer, lost)

    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private val question = RestoreQuestion(lost, restorer, viewModelScope)

    /** The home profiles a restore would meet now, for its question; null until they have been read. */
    internal val profileReader = question.reader

    /** The restore's question is about to be shown ([RestoreQuestion.opened]). */
    fun restoreQuestionOpened() = question.opened()

    /** When the home profile that is on was last exported to a file, WITH that profile (one value: the
     *  screen shows the time only beside the profile it is of); null until it has been read. */
    val lastBackup: StateFlow<LastBackup?> =
        lastExport.retryWhen<LastBackup?> { failure, _ ->
            if (failure !is IOException) return@retryWhen false
            emit(null)
            delay(1_000)
            true
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Writes the home profile [shownProfile] to [uri]: the one the export's button named when it was tapped.
     * With another profile on by now (the system's file picker was open meanwhile) nothing is written and the
     * screen says so; the picked document is left as it was ([exportToDocument]). The result names the profile
     * the file is of when there are several.
     */
    fun exportTo(uri: Uri, shownProfile: Long) = viewModelScope.launch {
        _events.emit(export(uri, shownProfile))
    }

    /** Restores [uri], held to the profiles its question showed ([asked]). */
    fun importFrom(uri: Uri, asked: RestoreProfiles): Boolean {
        if (profileReader.state.value !is RestoreProfileRead.Ready) return false
        viewModelScope.launch { _events.emit(question.restore(uri, asked)) }
        return true
    }

    internal companion object {
        const val TAG = "BackupVM"
    }
}
