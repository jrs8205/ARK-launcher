package org.arkikeskus.launcher.feature.updater

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.SettingsRepository
import javax.inject.Inject

data class UpdateUiState(
    val currentVersion: String = "",
    val isReleaseBuild: Boolean = false,
    val autoEnabled: Boolean = true,
    val lastCheckMs: Long = 0L,
    val checking: Boolean = false,
    val available: UpdateInfo? = null,
    val upToDate: Boolean = false,
    val error: Boolean = false,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: UpdateRepository,
    private val settings: SettingsRepository,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersion = currentVersionName(),
            isReleaseBuild = isReleaseBuild(context),
        ),
    )
    val state: StateFlow<UpdateUiState> = _state

    init {
        viewModelScope.launch { settings.autoUpdateEnabled.collect { e -> _state.update { it.copy(autoEnabled = e) } } }
        viewModelScope.launch { settings.updateLastCheck.collect { t -> _state.update { it.copy(lastCheckMs = t) } } }
    }

    fun checkNow() = viewModelScope.launch {
        if (!_state.value.isReleaseBuild) return@launch
        if (_state.value.checking) return@launch
        _state.update { it.copy(checking = true, upToDate = false, error = false) }
        runCatching { repository.checkLatest(currentVersionName()) }
            .onSuccess { info ->
                settings.setUpdateLastCheck(System.currentTimeMillis())
                _state.update { it.copy(checking = false, available = info, upToDate = info == null) }
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _state.update { it.copy(checking = false, error = true) }
            }
    }

    fun setAutoUpdate(enabled: Boolean) = viewModelScope.launch {
        settings.setAutoUpdateEnabled(enabled)
        if (enabled) UpdateScheduler.schedule(context) else UpdateScheduler.cancel(context)
    }

    fun installUpdate(info: UpdateInfo) = viewModelScope.launch {
        runCatching { installer.downloadAndInstall(info) }
            .onFailure { exc ->
                if (exc is kotlinx.coroutines.CancellationException) throw exc
                _state.update { it.copy(error = true) }
            }
    }

    private fun currentVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}

/** True for the shipped (non-debuggable) build. */
fun isReleaseBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
