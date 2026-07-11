package org.arkikeskus.launcher.feature.appdrawer

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.search.SearchAggregator
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.SearchResult
import org.arkikeskus.launcher.model.SearchResults
import javax.inject.Inject

/** A drawer folder resolved to its member [apps] (for rendering). */
data class DrawerFolderUi(val id: Long, val name: String, val apps: List<AppItem>)

data class AppDrawerUiState(
    val apps: List<AppItem> = emptyList(),
    val folders: List<DrawerFolderUi> = emptyList(),
    val query: String = "",
    val columns: Int = 4,
    val dockKeys: Set<String> = emptySet(),
    /** Visible dock capacity — the drawer hides "Add to dock" once this many favorites exist. */
    val dockColumns: Int = 4,
    val homeKeys: Set<String> = emptySet(),
    val showLabels: Boolean = true,
    val showSearch: Boolean = true,
    val badges: Map<String, Int> = emptyMap(),
    val showNotificationDots: Boolean = true,
    val notificationDotCount: Boolean = true,
    val notificationDotScale: Float = 1f,
    val useThemedIcons: Boolean = false,
    val iconPackPackage: String = "",
    val calc: SearchResult.Calculation? = null,
    val settingResults: List<SearchResult.Setting> = emptyList(),
    val contactResults: List<SearchResult.Contact> = emptyList(),
    val desktopLocked: Boolean = false,
    val showFrequentApps: Boolean = false,
    val frequentApps: List<AppItem> = emptyList(),
    val appLabelTextScale: Float = 1f,
    val drawerOpensAtTop: Boolean = true,
)

@HiltViewModel
class AppDrawerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    notificationBadgeRepository: NotificationBadgeRepository,
    private val searchAggregator: SearchAggregator,
    private val appUsageRepository: org.arkikeskus.launcher.data.AppUsageRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searchResults: Flow<SearchResults> = query
        .debounce { if (it.isBlank()) 0L else 200L } // contacts hit ContentResolver; avoid per-keystroke
        .mapLatest { searchAggregator.search(it) }    // mapLatest cancels the previous run

    val uiState: StateFlow<AppDrawerUiState> = combine(
        appRepository.apps,
        query,
        settingsRepository.settings,
        settingsRepository.dockFavorites,
        homeLayoutRepository.homeItems,
    ) { apps, q, settings, favorites, homeItems ->
        // Favorites whose app was uninstalled don't render in the dock (HomeViewModel drops them),
        // so they must not count toward its visible capacity either.
        val installed = apps.mapTo(HashSet()) { it.key }
        AppDrawerUiState(
            apps = apps,
            query = q,
            columns = settings.drawerColumns,
            dockKeys = favorites.filterTo(LinkedHashSet()) { it in installed },
            dockColumns = settings.dockColumns,
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
            useThemedIcons = settings.useThemedIcons,
            iconPackPackage = settings.iconPackPackage,
            desktopLocked = settings.desktopLocked,
            showFrequentApps = settings.showFrequentApps,
            appLabelTextScale = settings.appLabelTextScale,
            drawerOpensAtTop = settings.drawerOpensAtTop,
        )
    }.combine(notificationBadgeRepository.badges) { state, badges ->
        state.copy(badges = badges)
    }.combine(settingsRepository.hiddenApps) { state, hidden ->
        // Hide selected apps from the drawer (also excludes them from search results).
        if (hidden.isEmpty()) state else state.copy(apps = state.apps.filterNot { it.key in hidden })
    }.combine(settingsRepository.drawerFolders) { state, folders ->
        // While searching (or with no folders) keep a flat list so folder members stay findable.
        if (state.query.isNotBlank() || folders.isEmpty()) {
            state
        } else {
            val byKey = state.apps.associateBy { it.key }
            val folderUis = folders.map { f -> DrawerFolderUi(f.id, f.name, f.appKeys.mapNotNull { byKey[it] }) }
            val inFolder = folders.flatMap { it.appKeys }.toSet()
            state.copy(apps = state.apps.filterNot { it.key in inFolder }, folders = folderUis)
        }
    }.combine(searchResults) { state, results ->
        if (state.query.isBlank()) {
            state // idle drawer: apps + folders already prepared above
        } else {
            state.copy(
                apps = results.apps,          // hidden-aware app matches from AppSearchProvider
                folders = emptyList(),         // no folders while searching
                calc = results.calc,
                settingResults = results.settings,
                contactResults = results.contacts,
            )
        }
    }.combine(appUsageRepository.usage) { state, usage ->
        // Only the idle drawer gets a "most used" row; search keeps its own relevance order.
        if (!state.showFrequentApps || state.query.isNotBlank()) {
            state
        } else {
            val now = System.currentTimeMillis()
            val ranked = state.apps
                .mapNotNull { app ->
                    usage[app.key]?.let { app to org.arkikeskus.launcher.data.AppUsageRepository.currentScore(it, now) }
                }
                .filter { it.second > 0f }
                .sortedWith(
                    compareByDescending<Pair<AppItem, Float>> { it.second }
                        .thenBy { it.first.label.lowercase() },
                )
                .take(state.columns) // one row = drawerColumns; reacts to the icon-count setting
                .map { it.first }
            state.copy(frequentApps = ranked)
        }
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

    /** Drag-and-drop from the drawer onto a specific home cell (free cell, or first free if taken). */
    fun addToHomeAt(appItem: AppItem, page: Int, cellX: Int, cellY: Int) = viewModelScope.launch {
        val columns = settingsRepository.settings.first().homeColumns
        homeLayoutRepository.placeAt(appItem, page, cellX, cellY, columns)
    }

    fun removeFromHome(appItem: AppItem) = viewModelScope.launch { homeLayoutRepository.removeFromHome(appItem) }

    /** Stores a pinned shortcut on home (system-level pin done by the caller, which has a Context). */
    fun addPinnedShortcut(packageName: String, shortcutId: String, userSerial: Long) = viewModelScope.launch {
        val columns = settingsRepository.settings.first().homeColumns
        homeLayoutRepository.addShortcut(packageName, shortcutId, userSerial, columns)
    }

    /** Pins [item] in the system (IO — the Binder round-trips must not run on the main thread) and
     *  places it on the home grid only if the system pin succeeded (else it would be a dead cell). */
    fun pinShortcut(item: org.arkikeskus.launcher.ui.AppShortcuts.Item) = viewModelScope.launch {
        if (org.arkikeskus.launcher.ui.AppShortcuts.pin(context, item)) {
            addPinnedShortcut(item.packageName, item.id, item.userSerial)
        }
    }

    /** Sets a custom display name for an app (blank/null clears it back to the system label). */
    fun setCustomLabel(key: String, label: String?) =
        viewModelScope.launch { settingsRepository.setCustomLabel(key, label) }

    // --- Drawer folders --------------------------------------------------------------------------
    fun renameDrawerFolder(id: Long, name: String) =
        viewModelScope.launch { settingsRepository.renameDrawerFolder(id, name) }

    fun deleteDrawerFolder(id: Long) =
        viewModelScope.launch { settingsRepository.deleteDrawerFolder(id) }

    fun addAppsToDrawerFolder(id: Long, keys: List<String>) =
        viewModelScope.launch { settingsRepository.addAppsToDrawerFolder(id, keys) }

    fun removeAppFromDrawerFolder(id: Long, key: String) =
        viewModelScope.launch { settingsRepository.removeAppFromDrawerFolder(id, key) }
}
