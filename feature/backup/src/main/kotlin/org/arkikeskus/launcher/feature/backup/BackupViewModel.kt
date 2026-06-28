package org.arkikeskus.launcher.feature.backup

import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.UserManager
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
        }.onSuccess { _events.emit(BackupEvent.Exported(uri.lastPathSegment ?: "")) }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _events.emit(BackupEvent.Failed(it.message ?: "export failed"))
            }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("Could not open input stream")
            val doc = BackupCodec.decode(json)
            val apps = appRepository.apps.first()
            val mainSerial = mainUserSerial()
            backupRepository.restoreDocument(
                doc = doc,
                mainUserSerial = mainSerial,
                installedAppKeys = apps.map { "${it.packageName}/${it.className}" }.toSet(),
                installedPackages = apps.map { it.packageName }.toSet(),
            )
        }.onSuccess { _events.emit(BackupEvent.Restored(it.restored, it.skipped)) }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (it is BackupFormatException || it is JSONException) _events.emit(BackupEvent.InvalidFile)
                else _events.emit(BackupEvent.Failed(it.message ?: "restore failed"))
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
                    DriveRestClient(token, driveHttp).upload(
                        "arkikeskus-backup-$nowMs.json", json,
                    )
                }
                settings.setDriveLastBackup(nowMs, hash)
                nowMs
            } else {
                null // already up-to-date; skip upload
            }
        }.onSuccess { uploadedAt ->
            _driveState.update { s ->
                s.copy(isLoading = false, lastBackupMs = uploadedAt ?: s.lastBackupMs)
            }
            _events.emit(BackupEvent.DriveUploaded)
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            _driveState.update { it.copy(isLoading = false) }
            _events.emit(BackupEvent.Failed(it.message ?: "Drive upload failed"))
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
            _driveState.update { it.copy(isLoading = false) }
            _events.emit(BackupEvent.Failed(it.message ?: "Drive list failed"))
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
            val mainSerial = mainUserSerial()
            backupRepository.restoreDocument(
                doc = doc,
                mainUserSerial = mainSerial,
                installedAppKeys = apps.map { "${it.packageName}/${it.className}" }.toSet(),
                installedPackages = apps.map { it.packageName }.toSet(),
            )
        }.onSuccess { result ->
            _driveState.update { it.copy(isLoading = false) }
            _events.emit(BackupEvent.Restored(result.restored, result.skipped))
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            _driveState.update { it.copy(isLoading = false) }
            if (it is BackupFormatException || it is JSONException) _events.emit(BackupEvent.InvalidFile)
            else _events.emit(BackupEvent.Failed(it.message ?: "Drive restore failed"))
        }
    }

    /** Called by the Screen when Drive authorization fails (e.g. user cancels consent). */
    fun emitAuthFailed() = viewModelScope.launch { _events.emit(BackupEvent.AuthFailed) }

    private fun mainUserSerial(): Long = runCatching {
        context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
    }.getOrDefault(0L)
}
