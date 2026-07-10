package org.arkikeskus.launcher.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.IconPackInfo
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    private val iconPackRepository: IconPackRepository,
    appRepository: AppRepository,
) : ViewModel() {

    val settings: StateFlow<LauncherSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LauncherSettings(),
    )

    /** Installed icon packs (queried off-main once), for the picker. */
    val iconPacks: StateFlow<List<IconPackInfo>> =
        flow { emit(iconPackRepository.installedPacks()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All launchable apps (sorted), for the hidden-apps manager list. */
    val apps: StateFlow<List<AppItem>> = appRepository.apps
        .map { list -> list.sortedBy { it.label.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Keys currently hidden from the drawer. */
    val hiddenKeys: StateFlow<Set<String>> = repository.hiddenApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setAppHidden(key: String, hidden: Boolean) = update { repository.setAppHidden(key, hidden) }

    /** Creates a new empty folder in the app drawer (named [name]); it's filled from the drawer. */
    fun createDrawerFolder(name: String) = update { repository.createDrawerFolder(name) }

    fun setSwipeUp(value: Boolean) = update { repository.setSwipeUpForDrawer(value) }
    fun setSwipeDown(value: Boolean) = update { repository.setSwipeDownForNotifications(value) }
    fun setDockEnabled(value: Boolean) = update { repository.setDockEnabled(value) }
    fun setDockColumns(value: Int) = update { repository.setDockColumns(value) }
    fun setDrawerColumns(value: Int) = update { repository.setDrawerColumns(value) }
    fun setShowDrawerSearch(value: Boolean) = update { repository.setShowDrawerSearch(value) }
    fun setShowFrequentApps(value: Boolean) = update { repository.setShowFrequentApps(value) }
    fun setDrawerOpensAtTop(value: Boolean) = update { repository.setDrawerOpensAtTop(value) }
    fun setHomeColumns(value: Int) = viewModelScope.launch {
        // Shrinking the grid can leave shortcuts at an out-of-range cellX; repack them back on-screen
        // before the new (smaller) column count reaches the home screen, so nothing draws off-grid.
        val old = repository.settings.first().homeColumns
        if (value < old) homeLayoutRepository.reflow(value)
        repository.setHomeColumns(value)
    }
    fun setShowDockLabels(value: Boolean) = update { repository.setShowDockLabels(value) }
    fun setShowHomeLabels(value: Boolean) = update { repository.setShowHomeLabels(value) }
    fun setShowDrawerLabels(value: Boolean) = update { repository.setShowDrawerLabels(value) }
    fun setDockBackgroundOpacity(value: Float) = update { repository.setDockBackgroundOpacity(value) }
    fun setShowPageIndicator(value: Boolean) = update { repository.setShowPageIndicator(value) }
    fun setShowNotificationDots(value: Boolean) = update { repository.setShowNotificationDots(value) }
    fun setNotificationDotCount(value: Boolean) = update { repository.setNotificationDotCount(value) }
    fun setNotificationDotScale(value: Float) = update { repository.setNotificationDotScale(value) }
    fun setUseThemedIcons(value: Boolean) = update { repository.setUseThemedIcons(value) }
    fun setIconPack(pkg: String) = update { repository.setIconPackPackage(pkg) }
    fun setSearchContacts(value: Boolean) = update { repository.setSearchContacts(value) }

    /** Sets (or clears, for null) the app launched by the left-edge home swipe. */
    fun setLeftSwipeAppKey(key: String?) = update { repository.setLeftSwipeAppKey(key) }

    fun setDesktopLocked(value: Boolean) = update { repository.setDesktopLocked(value) }

    fun setAppLabelTextScale(value: Float) = update { repository.setAppLabelTextScale(value) }
    fun setAppLabelColor(argb: Int) = update { repository.setAppLabelColor(argb) }
    fun setShowStatusBar(value: Boolean) = update { repository.setShowStatusBar(value) }
    fun setShowWeather(value: Boolean) = update { repository.setShowWeather(value) }
    fun setNotificationWidgetCountStyle(value: String) = update { repository.setNotificationWidgetCountStyle(value) }
    fun setHideSystemStatusBar(value: Boolean) = update { repository.setHideSystemStatusBar(value) }
    fun setStatusBarScrimOpacity(value: Float) = update { repository.setStatusBarScrimOpacity(value) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
