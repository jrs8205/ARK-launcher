package org.arkikeskus.launcher.feature.appdrawer

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.PauseOutcome
import org.arkikeskus.launcher.data.PlaceResult
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.WorkProfileRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.search.SearchAggregator
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.SearchResult
import org.arkikeskus.launcher.model.SearchResults
import org.arkikeskus.launcher.ui.AppLaunch
import org.arkikeskus.launcher.ui.HomeNotice
import javax.inject.Inject

/** A drawer folder resolved to its member [apps] (for rendering), with the [icon] the user picked
 *  for it in place of its preview, if any. */
data class DrawerFolderUi(val id: Long, val name: String, val apps: List<AppItem>, val icon: CustomIcon? = null)

/** The work profile as the Work tab needs it; see `WorkProfile` in core/data for the fields. */
data class WorkProfileUi(val paused: Boolean, val canPause: Boolean, val transitioning: Boolean)

data class AppDrawerUiState(
    /** The Personal grid while idle; the flat search results while searching. */
    val apps: List<AppItem> = emptyList(),
    /** The Work grid while idle (empty without a work profile and while searching). */
    val workApps: List<AppItem> = emptyList(),
    val folders: List<DrawerFolderUi> = emptyList(),
    /** What a folder's "Add apps" sheet offers: the visible apps of both profiles not in a folder. */
    val folderCandidates: List<AppItem> = emptyList(),
    /** Non-null only when the Work tab has something to show (see [workTabVisible]). */
    val workProfile: WorkProfileUi? = null,
    /** The tab the pager shows; always Personal while [workProfile] is null. */
    val selectedTab: DrawerTab = DrawerTab.Personal,
    val query: String = "",
    val columns: Int = 4,
    val dockKeys: Set<String> = emptySet(),
    /** Visible dock capacity — the drawer hides "Add to dock" once this many favorites exist. */
    val dockColumns: Int = 4,
    val homeKeys: Set<String> = emptySet(),
    val showLabels: Boolean = true,
    /** The name under a folder tile (issue #17); labels off hide it too. */
    val showFolderNames: Boolean = true,
    val showSearch: Boolean = true,
    val badges: Map<String, Int> = emptyMap(),
    val showNotificationDots: Boolean = true,
    val notificationDotCount: Boolean = true,
    val notificationDotScale: Float = 1f,
    val useThemedIcons: Boolean = false,
    val iconPackPackage: String = "",
    val calc: SearchResult.Calculation? = null,
    /** The query on screen has no results yet (the debounce or the search is still running): the
     *  lists are the previous query's, so "No results" must wait. */
    val searchPending: Boolean = false,
    val settingResults: List<SearchResult.Setting> = emptyList(),
    val contactResults: List<SearchResult.Contact> = emptyList(),
    val desktopLocked: Boolean = false,
    val showFrequentApps: Boolean = false,
    val frequentApps: List<AppItem> = emptyList(),
    val appLabelTextScale: Float = 1f,
    val iconScale: Float = 1f,
    /** Labels may wrap onto a second line (Settings ▸ App drawer ▸ Two-line labels). */
    val twoLineLabels: Boolean = true,
    val drawerOpensAtTop: Boolean = true,
    /** The folders listed A-Z rather than in the user's order; the editor hides its moves then. */
    val sortDrawerFolders: Boolean = false,
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
    private val workProfileRepository: WorkProfileRepository,
    private val customIcons: CustomIconStore,
) : ViewModel() {

    private val _notices = Channel<HomeNotice>(Channel.BUFFERED)

    /** One-shot notices for the screen to show, each delivered once (see [HomeNotice]). */
    val notices: Flow<HomeNotice> = _notices.receiveAsFlow()

    /** Queues a notice for the screen; the buffer absorbs a burst while nothing collects yet. */
    private fun notify(notice: HomeNotice) {
        _notices.trySend(notice)
    }

    private val query = MutableStateFlow("")
    private val selectedTabFlow = MutableStateFlow(DrawerTab.Personal)

    /** The results, tagged with the query they answer: the raw query reaches the state at once, the
     *  results only after the debounce and the search, and the screen must tell the two apart. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searchResults: Flow<Pair<String, SearchResults>> = combine(
        query.debounce { if (it.isBlank()) 0L else 200L }, // contacts hit ContentResolver; avoid per-keystroke
        appRepository.apps,                                 // install/uninstall/rename re-runs a live query
    ) { q, _ -> q }
        .mapLatest { it to searchAggregator.search(it) }    // mapLatest cancels the previous run

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
            showFolderNames = settings.showDrawerFolderNames,
            showSearch = settings.showDrawerSearch,
            showNotificationDots = settings.showNotificationDots,
            notificationDotCount = settings.notificationDotCount,
            notificationDotScale = settings.notificationDotScale,
            useThemedIcons = settings.useThemedIcons,
            iconPackPackage = settings.iconPackPackage,
            desktopLocked = settings.desktopLocked,
            showFrequentApps = settings.showFrequentApps,
            appLabelTextScale = settings.appLabelTextScale,
            iconScale = settings.iconScale,
            twoLineLabels = settings.twoLineDrawerLabels,
            drawerOpensAtTop = settings.drawerOpensAtTop,
            sortDrawerFolders = settings.sortDrawerFolders,
        )
    }.combine(notificationBadgeRepository.badges) { state, badges ->
        state.copy(badges = badges)
    }.let { base ->
        combine(
            base,
            settingsRepository.hiddenApps,
            settingsRepository.drawerFolders,
            workProfileRepository.workProfile,
        ) { state, hidden, folders, profile ->
            // Hidden apps leave every list (the search results too: AppSearchProvider filters them
            // itself), the work profile's apps go to the Work grid, folders take their members out
            // of the Personal grid only. While searching keep a flat list so folder members stay
            // findable.
            val partition = partitionDrawer(
                apps = state.apps,
                hidden = hidden,
                folders = if (state.query.isNotBlank()) emptyList() else folders,
                workSerial = profile?.userSerial,
                sortFolders = state.sortDrawerFolders,
            )
            state.copy(
                apps = partition.personal,
                workApps = partition.work,
                folders = partition.folders,
                folderCandidates = partition.folderCandidates,
                workProfile = profile
                    ?.takeIf { workTabVisible(it, partition.work) }
                    ?.let { WorkProfileUi(it.paused, it.canPause, it.transitioning) },
            )
        }
    }.combine(settingsRepository.folderIcons) { state, icons ->
        if (icons.isEmpty()) state else state.copy(folders = state.folders.map { it.copy(icon = icons[FolderIconKey.drawer(it.id)]) })
    }.combine(searchResults) { state, (answered, results) ->
        if (state.query.isBlank()) {
            state // idle drawer: apps + folders already prepared above
        } else {
            state.copy(
                apps = results.apps,          // hidden-aware app matches from AppSearchProvider
                workApps = emptyList(),        // one flat list across both profiles
                folders = emptyList(),         // no folders while searching
                calc = results.calc,
                settingResults = results.settings,
                contactResults = results.contacts,
                searchPending = answered != state.query,
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
    }.combine(selectedTabFlow) { state, tab ->
        // A Work selection only means something while the Work tab exists: hiding the last work
        // app, or the profile going, lands the pager on Personal.
        state.copy(selectedTab = effectiveTab(tab, hasWorkTab = state.workProfile != null))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppDrawerUiState(),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun hideApp(appItem: AppItem) = viewModelScope.launch { settingsRepository.setAppHidden(appItem.key, true) }

    fun selectTab(tab: DrawerTab) {
        selectedTabFlow.value = tab
    }

    /** Pauses or unpauses the work profile; only the default launcher may, so the other case gets a notice. */
    fun setWorkPaused(paused: Boolean) = viewModelScope.launch {
        if (workProfileRepository.setWorkPaused(paused) == PauseOutcome.NotDefaultLauncher) {
            notify(HomeNotice.WorkAppsNeedDefault)
        }
    }

    /** Launches [appItem]; returns whether it started, so the drawer only closes on success. */
    /** Launches [appItem], with a zoom from its icon when [from] says where it was tapped. */
    fun onAppClick(appItem: AppItem, from: AppLaunch? = null): Boolean =
        appRepository.launch(appItem, from?.sourceBounds, from?.options)
            .onFailure { Log.w("AppDrawerViewModel", "Failed to launch ${appItem.key}", it) }
            .isSuccess

    fun addToDock(appItem: AppItem) = viewModelScope.launch { settingsRepository.addToDock(appItem.key) }

    fun removeFromDock(appItem: AppItem) = viewModelScope.launch { settingsRepository.removeFromDock(appItem.key) }

    fun addToHome(appItem: AppItem) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        if (!homeLayoutRepository.addToHome(appItem, s.homeColumns, s.homeRows)) notify(HomeNotice.HomeFull)
    }

    /** Drag-and-drop from the drawer onto a specific home cell: a free cell takes the icon, an app
     *  there makes a folder (named [folderName]) of the two, a folder there takes it in, anything
     *  else sends it to the first free cell. */
    fun addToHomeAt(appItem: AppItem, page: Int, cellX: Int, cellY: Int, folderName: String) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        val result = homeLayoutRepository.placeAt(appItem, page, cellX, cellY, s.homeColumns, s.homeRows, folderName)
        if (result == PlaceResult.HomeFull) {
            notify(HomeNotice.HomeFull)
        }
    }

    fun removeFromHome(appItem: AppItem) = viewModelScope.launch { homeLayoutRepository.removeFromHome(appItem) }

    /** Stores a pinned shortcut on home; false when every page the pager shows is full. */
    private suspend fun storePinnedShortcut(packageName: String, shortcutId: String, userSerial: Long): Boolean {
        val s = settingsRepository.settings.first()
        return homeLayoutRepository.addShortcut(packageName, shortcutId, userSerial, s.homeColumns, s.homeRows)
    }

    /** Pins [item] in the system (IO — the Binder round-trips must not run on the main thread) and
     *  places it on the home grid only if the system pin succeeded (else it would be a dead cell).
     *  When home has no room for it the system pin is released again — re-pinned to the ids home
     *  shows — so the system never holds a shortcut home cannot show. */
    fun pinShortcut(item: org.arkikeskus.launcher.ui.AppShortcuts.Item) = viewModelScope.launch {
        ShortcutPinLock.withLock {
            if (!org.arkikeskus.launcher.ui.AppShortcuts.pin(context, item)) return@withLock
            if (!storePinnedShortcut(item.packageName, item.id, item.userSerial)) {
                val remaining = homeLayoutRepository.homeShortcutIds(item.packageName, item.userSerial)
                org.arkikeskus.launcher.ui.AppShortcuts.setPinned(context, item.packageName, item.userSerial, remaining)
                notify(HomeNotice.HomeFull)
            }
        }
    }

    /** Sets a custom display name for an app (blank/null clears it back to the system label). */
    fun setCustomLabel(key: String, label: String?) =
        viewModelScope.launch { settingsRepository.setCustomLabel(key, label) }

    /** "Edit" saved for [app]: its name (null = left as it was) and its icon (null = the app's own). */
    fun editApp(app: AppItem, name: String?, icon: CustomIcon?) = viewModelScope.launch {
        if (name != null) settingsRepository.setCustomLabel(app.key, name)
        customIcons.setAppIcon(app.key, icon)
    }

    /** "Reset to default" in Edit: the app's own name and icon again. */
    fun resetApp(app: AppItem) = viewModelScope.launch {
        settingsRepository.setCustomLabel(app.key, null)
        customIcons.setAppIcon(app.key, null)
    }

    /** The icon picked for a drawer folder (null = back to its preview). */
    fun setDrawerFolderIcon(id: Long, icon: CustomIcon?) =
        viewModelScope.launch { customIcons.setFolderIcon(FolderIconKey.drawer(id), icon) }

    // --- Drawer folders --------------------------------------------------------------------------
    fun renameDrawerFolder(id: Long, name: String) =
        viewModelScope.launch { settingsRepository.renameDrawerFolder(id, name) }

    fun deleteDrawerFolder(id: Long) =
        viewModelScope.launch { customIcons.deleteDrawerFolder(id) }

    fun addAppsToDrawerFolder(id: Long, keys: List<String>) =
        viewModelScope.launch { settingsRepository.addAppsToDrawerFolder(id, keys) }

    fun removeAppFromDrawerFolder(id: Long, key: String) =
        viewModelScope.launch { settingsRepository.removeAppFromDrawerFolder(id, key) }

    /** Moves a drawer folder one place earlier ([by] = -1) or later (+1) among the folders. */
    fun moveDrawerFolder(id: Long, by: Int) =
        viewModelScope.launch { settingsRepository.moveDrawerFolder(id, by) }
}
