package org.arkikeskus.launcher.feature.backup

import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.UserManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupFormatException
import org.arkikeskus.launcher.data.backup.BackupRepository
import org.json.JSONException
import javax.inject.Inject

sealed interface BackupEvent {
    data class Exported(val name: String) : BackupEvent
    data class Restored(val restored: Int, val skipped: Int) : BackupEvent
    data object InvalidFile : BackupEvent
    data class Failed(val message: String) : BackupEvent
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val appRepository: AppRepository,
) : ViewModel() {

    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

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

    private fun mainUserSerial(): Long = runCatching {
        context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
    }.getOrDefault(0L)
}
