package org.arkikeskus.launcher.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject

data class HomeUiState(
    val settings: LauncherSettings = LauncherSettings(),
    val dockApps: List<AppItem> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appRepository: AppRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        settingsRepository.dockFavorites,
        appRepository.apps,
    ) { settings, favoriteKeys, apps ->
        val byKey = apps.associateBy { it.key }
        val dockApps = favoriteKeys.mapNotNull { byKey[it] }.take(settings.dockColumns)
        HomeUiState(settings = settings, dockApps = dockApps)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun launch(appItem: AppItem) = appRepository.launch(appItem)
}
