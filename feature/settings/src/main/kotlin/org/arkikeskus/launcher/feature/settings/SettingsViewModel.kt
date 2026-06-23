package org.arkikeskus.launcher.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<LauncherSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LauncherSettings(),
    )

    fun setSwipeUp(value: Boolean) = update { repository.setSwipeUpForDrawer(value) }
    fun setSwipeDown(value: Boolean) = update { repository.setSwipeDownForNotifications(value) }
    fun setDockEnabled(value: Boolean) = update { repository.setDockEnabled(value) }
    fun setDockColumns(value: Int) = update { repository.setDockColumns(value) }
    fun setDrawerColumns(value: Int) = update { repository.setDrawerColumns(value) }
    fun setHomeColumns(value: Int) = update { repository.setHomeColumns(value) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
