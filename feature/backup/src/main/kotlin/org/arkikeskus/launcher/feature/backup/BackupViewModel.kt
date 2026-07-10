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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupFormatException
import org.arkikeskus.launcher.data.backup.BackupRepository
import org.arkikeskus.launcher.feature.backup.drive.DriveFile
import org.arkikeskus.launcher.feature.backup.drive.DriveRestClient
import org.json.JSONException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

sealed interface BackupEvent {
    data class Exported(val name: String) : BackupEvent
    data class Restored(val restored: Int, val skipped: Int) : BackupEvent
    data object InvalidFile : BackupEvent
    data class Failed(val message: String) : BackupEvent
    data object DriveUploaded : BackupEvent
    data object AuthFailed : BackupEvent
}

data class DriveState(
    val enabled: Boolean = false,
    val lastBackupMs: Long = 0L,
    val availableFiles: List<DriveFile> = emptyList(),
    val isLoading: Boolean = false,
    val intervalDays: Int = 1,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    /** True when the periodic Drive backup has failed repeatedly (shown as a warning banner). */
    val failing: Boolean = false,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val appRepository: AppRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private val _driveState = MutableStateFlow(DriveState())
    val driveState: StateFlow<DriveState> = _driveState.asStateFlow()

    /** Epoch-ms timestamp of the last successful local file export (0 = never), for the file card. */
    private val _localLastBackupMs = MutableStateFlow(0L)
    val localLastBackupMs: StateFlow<Long> = _localLastBackupMs.asStateFlow()

    /** OkHttpClient shared across Drive calls; explicit timeouts prevent network stalls. */
    private val driveHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        // Keep driveState in sync with the DataStore so it reflects changes from restores as well.
        viewModelScope.launch {
            settings.driveEnabled.collect { enabled ->
                _driveState.update { it.copy(enabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.driveLastBackupTime.collect { time ->
                _driveState.update { it.copy(lastBackupMs = time) }
            }
        }
        viewModelScope.launch {
            settings.localLastBackupTime.collect { time -> _localLastBackupMs.value = time }
        }
        viewModelScope.launch {
            settings.driveIntervalDays.collect { d -> _driveState.update { it.copy(intervalDays = d) } }
        }
        viewModelScope.launch {
            settings.driveWifiOnly.collect { w -> _driveState.update { it.copy(wifiOnly = w) } }
        }
        viewModelScope.launch {
            settings.driveChargingOnly.collect { c -> _driveState.update { it.copy(chargingOnly = c) } }
        }
        viewModelScope.launch {
            settings.driveBackupFailing.collect { f -> _driveState.update { it.copy(failing = f) } }
        }
    }

    private val appVersion: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

    fun exportTo(uri: Uri) = viewModelScope.launch {
        runCatching {
            val doc = backupRepository.exportDocument(createdAt = System.currentTimeMillis(), appVersion = appVersion)
            context.contentResolver.openOutputStream(uri)?.use { it.write(BackupCodec.encode(doc).toByteArray()) }
                ?: error("Could not open output stream")
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
            val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("Could not open input stream")
            val doc = BackupCodec.decode(json)
            val apps = appRepository.apps.first()
            backupRepository.restoreDocument(
                doc = doc,
                installedAppKeys = apps.map { "${it.packageName}/${it.className}" }.toSet(),
                installedPackages = apps.map { it.packageName }.toSet(),
            )
        }.onSuccess { _events.emit(BackupEvent.Restored(it.restored, it.skipped)) }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (it is BackupFormatException || it is JSONException) _events.emit(BackupEvent.InvalidFile)
                else _events.emit(BackupEvent.Failed(it.message.orEmpty()))
            }
    }

    // -------------------------------------------------------------------------
    // Drive ops
    // -------------------------------------------------------------------------

    /**
     * Builds the current backup JSON and a content hash.
     * Hash is over [BackupCodec.encode] with [createdAt]=0 so identical content on different
     * days produces the same hash (dedup guard).
     */
    private suspend fun currentJsonAndHash(): Pair<String, String> {
        val doc = backupRepository.exportDocument(System.currentTimeMillis(), appVersion)
        val json = BackupCodec.encode(doc)
        val hashable = BackupCodec.encode(doc.copy(createdAt = 0L))
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(hashable.toByteArray()).joinToString("") { "%02x".format(it) }
        return json to hash
    }

    fun setDriveEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setDriveEnabled(enabled)
        // _driveState.enabled will auto-update via the settings.driveEnabled collector in init.
        if (enabled) rescheduleDrive() else BackupScheduler.cancel(context)
    }

    fun setDriveIntervalDays(days: Int) = viewModelScope.launch {
        settings.setDriveIntervalDays(days)
        if (settings.driveEnabledOnce()) rescheduleDrive()
    }

    fun setDriveWifiOnly(value: Boolean) = viewModelScope.launch {
        settings.setDriveWifiOnly(value)
        if (settings.driveEnabledOnce()) rescheduleDrive()
    }

    fun setDriveChargingOnly(value: Boolean) = viewModelScope.launch {
        settings.setDriveChargingOnly(value)
        if (settings.driveEnabledOnce()) rescheduleDrive()
    }

    /** (Re)schedules the periodic Drive worker with the current interval + network/charging options. */
    private suspend fun rescheduleDrive() {
        BackupScheduler.schedule(
            context,
            intervalDays = settings.driveIntervalDays.first(),
            wifiOnly = settings.driveWifiOnly.first(),
            chargingOnly = settings.driveChargingOnly.first(),
        )
    }

    /**
     * Uploads a new backup to Drive if the content has changed since the last upload.
     * [token] is a valid Drive access token obtained by the Screen after auth.
     */
    fun backupToDrive(token: String) = viewModelScope.launch {
        _driveState.update { it.copy(isLoading = true) }
        runCatching {
            val (json, hash) = currentJsonAndHash()
            if (hash != settings.driveLastHash()) {
                val nowMs = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val client = DriveRestClient(token, driveHttp)
                    client.upload("arkikeskus-launcher-backup-$nowMs.json", json)
                    // Keep Drive storage bounded from the manual path too (the periodic worker
                    // prunes as well, but it may be stuck failing — see driveBackupFailing).
                    client.pruneToNewest(BackupScheduler.KEEP_BACKUPS)
                }
                settings.setDriveLastBackup(nowMs, hash)
                nowMs
            } else {
                null // already up-to-date; skip upload
            }
        }.onSuccess { uploadedAt ->
            // A manual backup succeeded (or auth worked for the dedup check) → the failing
            // episode is over; the banner clears and the worker may notify again if it recurs.
            settings.clearDriveFailures()
            _driveState.update { s ->
                s.copy(isLoading = false, lastBackupMs = uploadedAt ?: s.lastBackupMs)
            }
            if (uploadedAt != null) _events.emit(BackupEvent.DriveUploaded)
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "Drive backup failed", it)
            _driveState.update { it.copy(isLoading = false) }
            _events.emit(BackupEvent.Failed(it.message.orEmpty()))
        }
    }

    /**
     * Lists backups from Drive and updates [driveState].availableFiles.
     * [token] is a valid Drive access token obtained by the Screen after auth.
     */
    fun listDriveBackups(token: String) = viewModelScope.launch {
        _driveState.update { it.copy(isLoading = true) }
        runCatching {
            withContext(Dispatchers.IO) { DriveRestClient(token, driveHttp).list() }
        }.onSuccess { files ->
            _driveState.update { it.copy(isLoading = false, availableFiles = files) }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "Drive list failed", it)
            _driveState.update { it.copy(isLoading = false) }
            _events.emit(BackupEvent.Failed(it.message.orEmpty()))
        }
    }

    /**
     * Downloads [fileId] from Drive and restores it using the same path as file import.
     * [token] is a valid Drive access token obtained by the Screen after auth.
     */
    fun restoreFromDrive(token: String, fileId: String) = viewModelScope.launch {
        _driveState.update { it.copy(isLoading = true) }
        runCatching {
            val json = withContext(Dispatchers.IO) { DriveRestClient(token, driveHttp).download(fileId) }
            val doc = BackupCodec.decode(json)
            val apps = appRepository.apps.first()
            backupRepository.restoreDocument(
                doc = doc,
                installedAppKeys = apps.map { "${it.packageName}/${it.className}" }.toSet(),
                installedPackages = apps.map { it.packageName }.toSet(),
            )
        }.onSuccess { result ->
            _driveState.update { it.copy(isLoading = false) }
            _events.emit(BackupEvent.Restored(result.restored, result.skipped))
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "Drive restore failed", it)
            _driveState.update { it.copy(isLoading = false) }
            if (it is BackupFormatException || it is JSONException) _events.emit(BackupEvent.InvalidFile)
            else _events.emit(BackupEvent.Failed(it.message.orEmpty()))
        }
    }

    /** Called by the Screen when Drive authorization fails (e.g. user cancels consent). */
    fun emitAuthFailed() = viewModelScope.launch { _events.emit(BackupEvent.AuthFailed) }

    private companion object {
        const val TAG = "BackupVM"
    }
}
