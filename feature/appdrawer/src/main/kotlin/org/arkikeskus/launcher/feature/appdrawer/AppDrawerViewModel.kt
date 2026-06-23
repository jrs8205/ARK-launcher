package org.arkikeskus.launcher.feature.appdrawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject

data class AppDrawerUiState(
    val apps: List<AppItem> = emptyList(),
    val query: String = "",
    val columns: Int = 4,
    val dockKeys: Set<String> = emptySet(),
    val homeKeys: Set<String> = emptySet(),
    val showLabels: Boolean = true,
)

@HiltViewModel
class AppDrawerViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<AppDrawerUiState> = combine(
        appRepository.apps,
        query,
        settingsRepository.settings,
        settingsRepository.dockFavorites,
        homeLayoutRepository.homeItems,
    ) { apps, q, settings, favorites, homeItems ->
        val filtered = if (q.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(q.trim(), ignoreCase = true) }
        }
        AppDrawerUiState(
            apps = filtered,
            query = q,
            columns = settings.drawerColumns,
            dockKeys = favorites.toSet(),
            homeKeys = homeItems.map { it.key }.toSet(),
            showLabels = settings.showDrawerLabels,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppDrawerUiState(),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onAppClick(appItem: AppItem) = appRepository.launch(appItem)

    fun addToDock(appItem: AppItem) = viewModelScope.launch { settingsRepository.addToDock(appItem.key) }

    fun removeFromDock(appItem: AppItem) = viewModelScope.launch { settingsRepository.removeFromDock(appItem.key) }

    fun addToHome(appItem: AppItem) = viewModelScope.launch {
        val columns = settingsRepository.settings.first().homeColumns
        homeLayoutRepository.addToHome(appItem, columns)
    }

    fun removeFromHome(appItem: AppItem) = viewModelScope.launch { homeLayoutRepository.removeFromHome(appItem) }
}
