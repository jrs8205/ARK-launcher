package org.arkikeskus.launcher.feature.appdrawer

import android.util.Log
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
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject

data class AppDrawerUiState(
    val apps: List<AppItem> = emptyList(),
    val query: String = "",
    val columns: Int = 4,
    val dockKeys: Set<String> = emptySet(),
    val homeKeys: Set<String> = emptySet(),
    val showLabels: Boolean = true,
    val showSearch: Boolean = true,
    val badges: Map<String, Int> = emptyMap(),
    val showNotificationDots: Boolean = true,
    val notificationDotCount: Boolean = true,
    val notificationDotScale: Float = 1f,
)

@HiltViewModel
class AppDrawerViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    notificationBadgeRepository: NotificationBadgeRepository,
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
            // Only apps placed directly on home (not folder rows or apps inside folders).
            homeKeys = homeItems
                .filter { it.containerId == HomeItemEntity.HOME && !it.isFolder }
                .map { it.key }
                .toSet(),
            showLabels = settings.showDrawerLabels,
            showSearch = settings.showDrawerSearch,
            showNotificationDots = settings.showNotificationDots,
            notificationDotCount = settings.notificationDotCount,
            notificationDotScale = settings.notificationDotScale,
        )
    }.combine(notificationBadgeRepository.badges) { state, badges ->
        state.copy(badges = badges)
    }.combine(settingsRepository.hiddenApps) { state, hidden ->
        // Hide selected apps from the drawer (also excludes them from search results).
        if (hidden.isEmpty()) state else state.copy(apps = state.apps.filterNot { it.key in hidden })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppDrawerUiState(),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun hideApp(appItem: AppItem) = viewModelScope.launch { settingsRepository.setAppHidden(appItem.key, true) }

    /** Launches [appItem]; returns whether it started, so the drawer only closes on success. */
    fun onAppClick(appItem: AppItem): Boolean =
        appRepository.launch(appItem)
            .onFailure { Log.w("AppDrawerViewModel", "Failed to launch ${appItem.key}", it) }
            .isSuccess

    fun addToDock(appItem: AppItem) = viewModelScope.launch { settingsRepository.addToDock(appItem.key) }

    fun removeFromDock(appItem: AppItem) = viewModelScope.launch { settingsRepository.removeFromDock(appItem.key) }

    fun addToHome(appItem: AppItem) = viewModelScope.launch {
        val columns = settingsRepository.settings.first().homeColumns
        homeLayoutRepository.addToHome(appItem, columns)
    }

    fun removeFromHome(appItem: AppItem) = viewModelScope.launch { homeLayoutRepository.removeFromHome(appItem) }
}
