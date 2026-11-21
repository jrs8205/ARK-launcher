package org.arkikeskus.launcher.feature.appdrawer

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.profile.homeProfileContent
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.DrawerWallpaper
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
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.folderStyle
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.SearchResult
import org.arkikeskus.launcher.model.SearchResults
import org.arkikeskus.launcher.ui.AppLaunch
import org.arkikeskus.launcher.ui.DockTarget
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.HomeNotice
import org.arkikeskus.launcher.model.IconEpochs
import org.arkikeskus.launcher.data.search.privateSpaceEntryOffered
import android.content.IntentSender
import org.arkikeskus.launcher.data.PrivateSettingsTarget
import org.arkikeskus.launcher.data.PrivateSpace
import org.arkikeskus.launcher.data.PrivateSpaceRepository
import javax.inject.Inject
import org.arkikeskus.launcher.model.IconShape

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
    /** Non-null only while the Private tab exists (see [privateTabVisible]). */
    val privateSpace: PrivateSpaceUi? = null,
    /** The private apps, A to Z, while the space is unlocked; never part of [apps] or of a search here. */
    val privateApps: List<AppItem> = emptyList(),
    /** The private apps' own icon re-fetch tokens (an updated private app moves its token here, not the shared one). */
    val privateIconEpochs: IconEpochs = IconEpochs(),
    /** The private space's matches of a search (only while it is unlocked); never part of [apps]. */
    val privateResults: List<AppItem> = emptyList(),
    /** A search offers the private space's own row, which unlocks it if needed and shows its tab. */
    val privateSpaceEntry: Boolean = false,
    /** The tabs that exist right now ([drawerTabs]); only Personal on a phone without profiles. */
    val tabs: List<DrawerTab> = listOf(DrawerTab.Personal),
    /** The tab a new pager starts on; always one of [tabs]. */
    val selectedTab: DrawerTab = DrawerTab.Personal,
    val query: String = "",
    val columns: Int = 4,
    /** The home profile this state is of: what the drawer asks of the home layout is asked for this one. */
    val homeProfileId: Long = org.arkikeskus.launcher.model.HomeProfile.FIRST_ID,
    val dockKeys: Set<String> = emptySet(),
    /** Folders in the dock: slots taken like [dockKeys]' apps. */
    val dockFolders: Int = 0,
    /** Visible dock capacity — the drawer hides "Add to dock" once this many entries exist. */
    val dockColumns: Int = 4,
    val homeKeys: Set<String> = emptySet(),
    /** The apps the user has hidden. None of them is in the lists; a search shows one only when the user has
     *  asked the search to find hidden apps (discussion #34), and its menu then does not offer to hide it. */
    val hiddenApps: Set<String> = emptySet(),
    /** Every app on the home surface as shown ([homeSurfaceKeys]): what [hideHomeApps] takes out of the list. */
    val onHomeKeys: Set<String> = emptySet(),
    /** Apps on the home surface leave the list (the search still finds them). */
    val hideHomeApps: Boolean = false,
    val showLabels: Boolean = true,
    /** The name under a folder tile (issue #17); labels off hide it too. */
    val showFolderNames: Boolean = true,
    val showSearch: Boolean = true,
    /** The drawer surface's opacity over the wallpaper (settings, issue #21). */
    val backgroundOpacity: Float = 1f,
    /** The drawer surface's ARGB colour, or null for the theme's own (settings, a Telegram request). */
    val backgroundColor: Int? = null,
    /** The grid labels' chosen ARGB colour, or null for the automatic black or white by the drawer's look. */
    val labelColor: Int? = null,
    /** The user's own picture behind the drawer, or null for the system wallpaper through the window. */
    val wallpaper: DrawerWallpaper? = null,
    /** How much the own picture is darkened for legibility (0 = as it is, 1 = black); the colour is off with a picture. */
    val pictureDim: Float = 0f,
    /** The wallpaper blur setting and its strength: an own picture is blurred inside the window by them. */
    val blurBehindDrawer: Boolean = false,
    val drawerBlurStrength: Float = 1f,
    val badges: Map<String, Int> = emptyMap(),
    val showNotificationDots: Boolean = true,
    val notificationDotCount: Boolean = true,
    val notificationDotScale: Float = 1f,
    val useThemedIcons: Boolean = false,
    val themeAllIcons: Boolean = false,
    val iconPackPackage: String = "",
    val iconShape: IconShape = IconShape.SYSTEM,
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
    /** Issue #33: the search field at the bottom of the drawer, the "most used" card right above it. */
    val searchAtBottom: Boolean = false,
    /** The folders listed A-Z rather than in the user's order; the editor hides its moves then. */
    val sortDrawerFolders: Boolean = false,
    /** The folders seated among the apps by name (issue #22); the editor hides its moves then too. */
    val mixDrawerFolders: Boolean = false,
    /** Icons per row in an open folder's card and editor (Settings > Folders > Folder columns). */
    val folderColumns: Int = 4,
    /** App names, icon size and row spacing of an open folder (Settings > Folders, shared with home). */
    val folderStyle: org.arkikeskus.launcher.model.FolderStyle = org.arkikeskus.launcher.model.FolderStyle(),
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
    private val privateSpace: PrivateSpaceRepository,
    private val customIcons: CustomIconStore,
    private val drawerWallpaper: DrawerWallpaperStore,
    private val systemUsage: org.arkikeskus.launcher.data.SystemUsageSync,
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

    /** The private space's search row was tapped: its tab is to be shown as soon as it exists (a hidden
     *  space's exists once Android has unlocked it). Given up when the drawer closes. */
    private val wantsPrivateTab = MutableStateFlow(false)

    /** The private space with the apps that go with it. */
    private val privateSpaceFlow =
        combine(privateSpace.state, privateSpace.apps, privateSpace.iconEpochs) { space, apps, epochs -> Triple(space, apps, epochs) }

    /** The results, tagged with the query they answer: the raw query reaches the state at once, the
     *  results only after the debounce and the search, and the screen must tell the two apart. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searchResults: Flow<Pair<String, SearchResults>> = combine(
        query.debounce { if (it.isBlank()) 0L else 200L }, // contacts hit ContentResolver; avoid per-keystroke
        appRepository.apps,                                 // install/uninstall/rename re-runs a live query
        // The private space locking, unlocking or changing its apps answers the query on screen again: its
        // apps, and for a hidden space its own row, must not outlive the lock in results nobody retyped.
        privateSpaceFlow,
    ) { q, _, _ -> q }
        .mapLatest { it to searchAggregator.search(it) }    // mapLatest cancels the previous run

    /**
     * The drawer's state without the private space's part and without the tabs: followed while the screen
     * reads it and for five seconds after, and from then on kept as it last was, so a returning screen has its
     * lists at once. Of the private space it holds only what a search answered ([AppDrawerUiState.privateResults]
     * and the space's own row), as the search left it. Only [uiState] reads it.
     */
    private val kept: StateFlow<AppDrawerUiState> = combine(
        appRepository.apps,
        query,
        // The settings and the home rows of ONE home profile: around a switch the two are for a moment of
        // two profiles, and such a combination never gets here (the dock's columns of one profile against
        // the dock rows of another).
        homeProfileContent(settingsRepository, homeLayoutRepository),
    ) { apps, q, content ->
        val settings = content.settings
        val homeItems = content.layout.items
        // Dock rows whose app was uninstalled don't render in the dock (HomeViewModel drops them),
        // so they must not count toward its visible capacity either; a dock folder always does.
        val installed = apps.mapTo(HashSet()) { it.key }
        val dockRows = homeItems.filter { it.containerId == HomeItemEntity.DOCK }
        AppDrawerUiState(
            apps = apps,
            query = q,
            columns = settings.drawerColumns,
            homeProfileId = settings.homeProfileId,
            dockKeys = dockRows.filter { it.isApp && it.key in installed }.mapTo(LinkedHashSet()) { it.key },
            dockFolders = dockRows.count { it.isFolder },
            dockColumns = settings.dockColumns,
            // Only apps placed directly on home (not folder rows or apps inside folders).
            homeKeys = homeItems
                .filter { it.containerId == HomeItemEntity.HOME && !it.isFolder }
                .map { it.key }
                .toSet(),
            // The apps on the home surface as shown: pages, home folders, and the dock as HomeViewModel shows it.
            onHomeKeys = homeSurfaceKeys(homeItems, installed, settings.dockEnabled, settings.dockColumns),
            hideHomeApps = settings.hideHomeAppsFromDrawer,
            showLabels = settings.showDrawerLabels,
            showFolderNames = settings.showDrawerFolderNames,
            showSearch = settings.showDrawerSearch,
            backgroundOpacity = settings.drawerBackgroundOpacity,
            backgroundColor = settings.drawerBackgroundColor,
            labelColor = settings.drawerLabelColor,
            pictureDim = settings.drawerPictureDim,
            blurBehindDrawer = settings.blurBehindDrawer,
            drawerBlurStrength = settings.drawerBlurStrength,
            showNotificationDots = settings.showNotificationDots,
            notificationDotCount = settings.notificationDotCount,
            notificationDotScale = settings.notificationDotScale,
            useThemedIcons = settings.useThemedIcons,
            themeAllIcons = settings.themeAllIcons,
            iconPackPackage = settings.iconPackPackage,
            iconShape = settings.iconShape,
            desktopLocked = settings.desktopLocked,
            showFrequentApps = settings.showFrequentApps,
            appLabelTextScale = settings.appLabelTextScale,
            iconScale = settings.drawerIconScale,
            twoLineLabels = settings.twoLineDrawerLabels,
            drawerOpensAtTop = settings.drawerOpensAtTop,
            searchAtBottom = settings.drawerSearchAtBottom,
            sortDrawerFolders = settings.sortDrawerFolders,
            mixDrawerFolders = settings.mixDrawerFolders,
            folderColumns = settings.folderColumns,
            folderStyle = settings.folderStyle(),
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
            // Hidden apps leave every list (the search results too, unless the user asked the search to find
            // them: AppSearchProvider decides), the work profile's apps go to the Work grid, folders take their members out
            // of the Personal grid only. While searching keep a flat list so folder members stay
            // findable.
            // With the setting on, apps on the home surface are hidden like hidden apps, but only from the
            // browsing list: a search keeps finding them (the search provider filters hidden apps itself).
            val hiddenNow = if (state.hideHomeApps && state.query.isBlank()) hidden + state.onHomeKeys else hidden
            val partition = partitionDrawer(
                apps = state.apps,
                hidden = hiddenNow,
                folders = if (state.query.isNotBlank()) emptyList() else folders,
                workSerial = profile?.userSerial,
                sortFolders = state.sortDrawerFolders,
            )
            state.copy(
                hiddenApps = hidden,
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
                privateResults = results.privateApps,
                privateSpaceEntry = results.privateSpaceEntry,
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
    }.combine(drawerWallpaper.current) { state, wallpaper ->
        state.copy(wallpaper = wallpaper)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppDrawerUiState(),
    )

    /**
     * [kept] with the private space and the tabs as the arguments say they stand. Everything of the private
     * space the screen shows is decided here and nowhere else.
     */
    private fun AppDrawerUiState.withPrivateSpace(
        space: PrivateSpace?,
        apps: List<AppItem>,
        iconEpochs: IconEpochs,
        stored: DrawerTab,
        wantsPrivate: Boolean,
    ): AppDrawerUiState {
        // The page exists while its tab does: not for a locked space whose entry is to stay hidden.
        val shown = space?.takeIf { privateTabVisible(it) }
        val open = shown != null && !shown.locked
        // (The state has a field of the repository's name: the repository is named in full here.)
        val repository = this@AppDrawerViewModel.privateSpace
        val page = shown?.let {
            PrivateSpaceUi(
                locked = it.locked,
                transitioning = it.transitioning,
                canInstall = !it.locked && repository.installSender() != null,
                canOpenSettings = !it.locked && repository.settingsTarget() != null,
            )
        }
        // A stored selection only means something while its tab exists: hiding the last work app,
        // or the profile going, lands the pager on Personal.
        val tabs = drawerTabs(hasWork = workProfile != null, hasPrivate = page != null)
        // Decided with the list itself, so a pager built for this list starts on the tab that was asked for.
        val tab = if (wantsPrivate && DrawerTab.Private in tabs) DrawerTab.Private else stored
        return copy(
            privateSpace = page,
            // A locked space never keeps the list of a moment ago.
            privateApps = if (open) apps else emptyList(),
            // Nor do the results a search left: the search answers again, but this is at once.
            privateResults = if (open) privateResults else emptyList(),
            // The space's own row too is held against the space as it stands NOW, not as it stood when the
            // search answered: a hidden space that locks must not stay named by the first letters of its name
            // until every search source (the contacts, say) has answered again.
            privateSpaceEntry = privateSpaceEntry && privateSpaceEntryOffered(context, query, space),
            privateIconEpochs = iconEpochs,
            tabs = tabs,
            selectedTab = effectiveTab(tab, tabs),
        )
    }

    /**
     * What the screen shows: [kept], with the private space and the tabs as they stand at the moment the value
     * is read or handed to a collector. That part is never taken from what was kept. The screen stops reading
     * while the launcher is off the screen, [kept] then stops following anything after five seconds, and what
     * a returning screen is handed first is that old state (the search has to answer again before a new one
     * exists). A space that locked meanwhile must show nothing of itself in it, not for one frame: so its part
     * is read from [PrivateSpaceRepository], which follows the system whoever reads it, each time anew.
     */
    val uiState: StateFlow<AppDrawerUiState> = ReadNowStateFlow(
        changes = merge<Any?>(kept, privateSpaceFlow, selectedTabFlow, wantsPrivateTab),
        read = {
            kept.value.withPrivateSpace(
                space = privateSpace.state.value,
                apps = privateSpace.apps.value,
                iconEpochs = privateSpace.iconEpochs.value,
                stored = selectedTabFlow.value,
                wantsPrivate = wantsPrivateTab.value,
            )
        },
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun hideApp(appItem: AppItem) = viewModelScope.launch { settingsRepository.setAppHidden(appItem.key, true) }

    fun selectTab(tab: DrawerTab) {
        selectedTabFlow.value = tab
        // The wish for the Private tab is met once the pager rests on it.
        if (tab == DrawerTab.Private) wantsPrivateTab.value = false
    }

    /**
     * The private space's own search row: the search ends, the space is unlocked if it is locked (Android
     * shows its prompt), and its tab is the one shown, at once or as soon as the unlock has given it one.
     */
    fun openPrivateSpace() {
        wantsPrivateTab.value = true
        if (privateSpace.state.value?.locked == true) setPrivateLocked(false)
        query.value = ""
    }

    /** The drawer closed: a wish for the Private tab that was not met (the unlock was cancelled) is given up. */
    fun onDrawerClosed() {
        wantsPrivateTab.value = false
    }

    /** Pauses or unpauses the work profile; only the default launcher may, so the other case gets a notice. */
    fun setWorkPaused(paused: Boolean) = viewModelScope.launch {
        if (workProfileRepository.setWorkPaused(paused) == PauseOutcome.NotDefaultLauncher) {
            notify(HomeNotice.WorkAppsNeedDefault)
        }
    }

    /** Locks or unlocks the private space; only the default launcher may, so the other case gets a notice. */
    fun setPrivateLocked(locked: Boolean) = viewModelScope.launch {
        if (privateSpace.setLocked(locked) == PauseOutcome.NotDefaultLauncher) {
            notify(HomeNotice.PrivateSpaceNeedsDefault)
        }
    }

    /**
     * Starts a private app through the private space's own start, which counts nothing. A failure is logged
     * without the app's key and without the exception's own text (it names the component): only its kind.
     */
    fun onPrivateAppClick(appItem: AppItem, from: AppLaunch? = null): Boolean =
        privateSpace.launch(appItem, from?.sourceBounds, from?.options)
            .onFailure { Log.w("AppDrawerViewModel", "Failed to launch a private app (${it.javaClass.simpleName})") }
            .isSuccess

    /** The drawer opened: Android's own hide setting has no broadcast, so it is read again now. And the
     *  "most used" card is about to be looked at: what was opened elsewhere since the last time is counted
     *  in, if the user has switched that on (off the main thread: it asks the system). */
    fun onDrawerOpened() {
        privateSpace.refresh()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { systemUsage.sync() } }
    }

    /** What the Install tile opens right now, or null. */
    fun privateInstallSender(): IntentSender? = privateSpace.installSender()

    /** What the Private tab's settings button opens right now, or null. */
    fun privateSettingsTarget(): PrivateSettingsTarget? = privateSpace.settingsTarget()

    /** Launches [appItem]; returns whether it started, so the drawer only closes on success. */
    /** Launches [appItem], with a zoom from its icon when [from] says where it was tapped. */
    fun onAppClick(appItem: AppItem, from: AppLaunch? = null): Boolean =
        appRepository.launch(appItem, from?.sourceBounds, from?.options)
            .onFailure { Log.w("AppDrawerViewModel", "Failed to launch ${appItem.key}", it) }
            .isSuccess

    /**
     * What the app's menu asks to be changed on the layout of the home profile [profile], the one the menu was
     * drawn from ([AppDrawerUiState.homeProfileId] of the state that said what the menu offers). [work] runs
     * while that profile is on and stays on; with another one on by now it does not run: the menu offered
     * "Remove from home" or "Add to dock" for the rows of ITS profile, and says nothing of another's.
     */
    private suspend fun onLayoutOf(profile: Long, work: suspend () -> Unit) {
        homeLayoutRepository.whileProfileIsOn(profile) { work() }
    }

    /** "Add to dock" of the app's menu, for the home profile [profile] ([onLayoutOf]). */
    fun addToDock(appItem: AppItem, profile: Long) =
        viewModelScope.launch { onLayoutOf(profile) { homeLayoutRepository.addToDock(appItem) } }

    /**
     * A drawer icon released on the dock at [target] (see [HomeDragController.dockTargetAt]): onto a
     * dock app it folds the two into a folder named [folderName], onto a dock folder it joins it,
     * between tiles it takes that slot. The tile indices are the dock's shown slots: its rows in
     * order, a folder always, an app only while it resolves, cut to the dock's columns.
     *
     * For the home profile [profile], the one the drawer showed when the icon was dropped ([onLayoutOf]): the
     * tile under the drop is looked up and the row written while that profile stays on. Looked up first and
     * written in a step that read the profile again, a switch between the two folded the icon with another
     * profile's app.
     */
    fun dropOnDock(appItem: AppItem, target: DockTarget, folderName: String, profile: Long) = viewModelScope.launch {
        onLayoutOf(profile) {
            val apps = appRepository.apps.first()
            val byKey = apps.associateBy { it.key }
            val shownKeys = apps.mapTo(HashSet()) { it.key }
            val columns = settingsRepository.settings.first().dockColumns
            val shown = homeLayoutRepository.homeItems.first()
                .filter { it.containerId == HomeItemEntity.DOCK && (it.isFolder || it.key in shownKeys) }
                .sortedWith(compareBy({ it.page }, { it.cellY }, { it.cellX }))
                .take(columns)
            val onto = (target as? DockTarget.Onto)?.let { shown.getOrNull(it.index) }
            val ontoApp = onto?.takeIf { it.isApp }?.let { byKey[it.key] }
            when {
                onto?.isFolder == true -> homeLayoutRepository.addToDockFolder(appItem, onto.id, fromContainer = null)
                ontoApp != null -> homeLayoutRepository.createDockFolder(ontoApp, appItem, fromContainer = null, name = folderName)
                else -> {
                    val index = when (target) {
                        is DockTarget.Between -> target.index
                        is DockTarget.Onto -> target.index
                    }
                    homeLayoutRepository.addToDockAt(appItem, index, shownKeys)
                }
            }
        }
    }

    /** "Remove from dock" of the app's menu, for the home profile [profile] ([onLayoutOf]). */
    fun removeFromDock(appItem: AppItem, profile: Long) =
        viewModelScope.launch { onLayoutOf(profile) { homeLayoutRepository.removeFromDock(appItem) } }

    /**
     * What the drawer, drawn from the state of the home profile [profile], asks to be ADDED to its layout. [work] is given that
     * profile's settings (its grid) and runs while the profile is on and stays on; with another profile on it does
     * not run, and null is returned. The screen hands [profile] over from the state it was drawn from: the grid
     * used to be read from the settings and the row stored in a transaction that read the profile again, so a
     * switch between the two stored the row in the other profile, at a cell counted for the first one's columns.
     */
    private suspend fun <T> onHomeOf(profile: Long, work: suspend (LauncherSettings) -> T): T? =
        homeLayoutRepository.whileProfileIsOn(profile) { work(settingsRepository.settings.first()) }

    /** Puts [appItem] at the first free cell of the home profile [profile] ([onHomeOf]). */
    fun addToHome(appItem: AppItem, profile: Long) = viewModelScope.launch {
        val added = onHomeOf(profile) { s -> homeLayoutRepository.addToHome(appItem, s.homeColumns, s.homeRows) }
        if (added == false) notify(HomeNotice.HomeFull)
    }

    /** Drag-and-drop from the drawer onto a specific home cell: a free cell takes the icon, an app
     *  there makes a folder (named [folderName]) of the two, a folder there takes it in, anything
     *  else sends it to the first free cell. For the home profile [profile], the one the drawer showed when
     *  the icon was dropped: its grid is read and the row stored while it stays on ([onHomeOf]), or nothing is
     *  placed. Read first and stored in a step that read the profile again, a switch between the two put the
     *  icon in another profile, at a cell counted for the first one's grid. */
    fun addToHomeAt(appItem: AppItem, page: Int, cellX: Int, cellY: Int, folderName: String, profile: Long) = viewModelScope.launch {
        val result = onHomeOf(profile) { s ->
            homeLayoutRepository.placeAt(appItem, page, cellX, cellY, s.homeColumns, s.homeRows, folderName)
        }
        if (result == PlaceResult.HomeFull) {
            notify(HomeNotice.HomeFull)
        }
    }

    /**
     * A folder of the drawer dropped on a home cell: a copy of it is made there (the drawer keeps its own), at
     * the cell when it is free, else at the first free one. A home folder holds two apps or more (one left with
     * a single app turns into that app), so a folder of one app lands as the app's own icon. An icon from an
     * icon pack goes with the copy; a picture of the user's does not (the file has one owner, and deleting
     * either folder's icon would take it from the other): the copy then shows the preview of its apps.
     *
     * For the home profile [profile], the one the drawer showed when the folder was dropped, as [addToHomeAt]:
     * the grid, the rows and the copy's icon (filed under its new row, among that profile's own values) are
     * one profile's.
     */
    fun addFolderToHomeAt(folder: DrawerFolderUi, page: Int, cellX: Int, cellY: Int, profile: Long) = viewModelScope.launch {
        val apps = folder.apps
        if (apps.isEmpty()) return@launch
        onHomeOf(profile) { s ->
            if (apps.size == 1) {
                val result = homeLayoutRepository.placeAt(apps.single(), page, cellX, cellY, s.homeColumns, s.homeRows, folder.name)
                if (result == PlaceResult.HomeFull) notify(HomeNotice.HomeFull)
                return@onHomeOf
            }
            val id = homeLayoutRepository.addFolderAt(folder.name, apps, page, cellX, cellY, s.homeColumns, s.homeRows)
            if (id == null) {
                notify(HomeNotice.HomeFull)
                return@onHomeOf
            }
            (folder.icon as? CustomIcon.FromPack)?.let { customIcons.setFolderIcon(FolderIconKey.home(id), it) }
        }
    }

    /** "Remove from home" of the app's menu, for the home profile [profile] ([onLayoutOf]). */
    fun removeFromHome(appItem: AppItem, profile: Long) =
        viewModelScope.launch { onLayoutOf(profile) { homeLayoutRepository.removeFromHome(appItem) } }

    /** Pins [item] in the system (IO — the Binder round-trips must not run on the main thread) and
     *  places it on the home grid only if the system pin succeeded (else it would be a dead cell).
     *  When home has no room for it the system pin is released again — re-pinned to the ids home
     *  shows — so the system never holds a shortcut home cannot show. For the home profile [profile],
     *  the one the drawer's state was of when the pin was offered ([onHomeOf]). */
    fun pinShortcut(item: org.arkikeskus.launcher.ui.AppShortcuts.Item, profile: Long) = viewModelScope.launch {
        // The profile's hold first, then the pin lock: the order every holder of both keeps.
        onHomeOf(profile) { s ->
            ShortcutPinLock.withLock {
                if (!org.arkikeskus.launcher.ui.AppShortcuts.pin(context, item)) return@withLock
                if (!homeLayoutRepository.addShortcut(item.packageName, item.id, item.userSerial, s.homeColumns, s.homeRows)) {
                    val remaining = homeLayoutRepository.homeShortcutIds(item.packageName, item.userSerial)
                    org.arkikeskus.launcher.ui.AppShortcuts.setPinned(context, item.packageName, item.userSerial, remaining)
                    notify(HomeNotice.HomeFull)
                }
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

    /**
     * What the folder editor asks of the drawer folder it shows, a folder of the home profile [profile]. A
     * drawer folder's id is its profile's own: a profile made as a copy has folders with the very same ids, so
     * the id alone does not say which folder is meant. [work] runs while that profile is on and stays on;
     * with another one on by now it does not run, and the other profile's folder of that id is left as it was.
     */
    private fun onFolderOf(profile: Long, work: suspend () -> Unit) =
        viewModelScope.launch { onLayoutOf(profile, work) }

    /** The icon picked for a drawer folder (null = back to its preview). */
    fun setDrawerFolderIcon(id: Long, icon: CustomIcon?, profile: Long) =
        onFolderOf(profile) { customIcons.setFolderIcon(FolderIconKey.drawer(id), icon) }

    // --- Drawer folders --------------------------------------------------------------------------
    fun renameDrawerFolder(id: Long, name: String, profile: Long) =
        onFolderOf(profile) { settingsRepository.renameDrawerFolder(id, name) }

    fun deleteDrawerFolder(id: Long, profile: Long) =
        onFolderOf(profile) { customIcons.deleteDrawerFolder(id) }

    fun addAppsToDrawerFolder(id: Long, keys: List<String>, profile: Long) =
        onFolderOf(profile) { settingsRepository.addAppsToDrawerFolder(id, keys) }

    fun removeAppFromDrawerFolder(id: Long, key: String, profile: Long) =
        onFolderOf(profile) { settingsRepository.removeAppFromDrawerFolder(id, key) }

    /** The folder editor's "Sort A-Z" (issue #32): the folder's apps in the order of their names, capitals
     *  aside, as the drawer lists apps and as a home folder's editor sorts its own. Done once: an app added
     *  later goes to the end again. */
    fun sortDrawerFolderByName(id: Long, profile: Long) = onFolderOf(profile) {
        val byName = appRepository.apps.first().sortedBy { it.label.lowercase() }.map { it.key }
        settingsRepository.sortDrawerFolder(id, byName)
    }

    /** Moves a drawer folder one place earlier ([by] = -1) or later (+1) among the folders. */
    fun moveDrawerFolder(id: Long, by: Int, profile: Long) =
        onFolderOf(profile) { settingsRepository.moveDrawerFolder(id, by) }
}

/**
 * A [StateFlow] that keeps no value: [read] computes it at the moment it is asked for. A collector is handed
 * that value at once, before anything else runs, and a new one after every one of [changes]; however late a
 * reader comes, it is never handed a value older than what [read] reads right then.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ReadNowStateFlow<T>(private val changes: Flow<*>, private val read: () -> T) : StateFlow<T> {
    override val value: T get() = read()
    override val replayCache: List<T> get() = listOf(read())
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        // Several changes waiting at once are one reading: the value is read when it is handed on.
        changes.conflate().map { read() }.onStart { emit(read()) }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
