package org.arkikeskus.launcher.feature.home

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.PlaceResult
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.AppLaunch
import org.arkikeskus.launcher.ui.AppShortcuts
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeNotice
import javax.inject.Inject

/** How long the themed status bar stays blanked after a heads-up post; SystemUI auto-dismisses a
 *  heads-up notification after ~5 s (its default decay) plus a hide animation, and we have no
 *  un-pin signal, so we time it out past that. 6 s left the system bar's dismissal animation
 *  overlapping our re-shown bar for ~1 s on a real WhatsApp heads-up (Pixel 8a, Android 17). */

/** Default grid footprint of the built-in smartspace widget (clamped to the column count). */
const val SMARTSPACE_DEFAULT_SPAN_X = 4
const val SMARTSPACE_DEFAULT_SPAN_Y = 2

/** Smallest allowed smartspace size — below 3 columns the clock + event row no longer fit. */
const val SMARTSPACE_MIN_SPAN_X = 3

/** Edit-frame default width of the built-in notifications widget (the picker ADDS it full-width —
 *  a narrower footprint can't center its icon group on an odd column count). */
const val NOTIFICATIONS_DEFAULT_SPAN_X = 4

/** Smallest allowed notifications-widget width — one icon + the overflow chip still fit. */
const val NOTIFICATIONS_MIN_SPAN_X = 2

/** Default AND minimum footprint of the built-in battery widget — app-icon sized. */
const val BATTERY_SPAN = 1

/** The people widget defaults to a few rows of tiles; the user stretches it to the whole grid. */
const val PEOPLE_DEFAULT_SPAN_Y = 3

/** Narrowest people widget: two quiet tiles, or one tile with content. */
const val PEOPLE_MIN_SPAN_X = 2

/** A page-menu operation's result when storage refused it (the layout is left as it was). */
internal const val PAGE_FAILED = -2

/** Something placed at a free cell on a home page — an app shortcut or a folder. */
sealed interface HomeEntry {
    val page: Int
    val cellX: Int
    val cellY: Int
}

/** An app shortcut placed at a free cell on a home page. */
data class PlacedApp(
    val app: AppItem,
    override val page: Int,
    override val cellX: Int,
    override val cellY: Int,
) : HomeEntry

/** A folder placed at a free cell, holding [apps] (resolved, in order). */
data class PlacedFolder(
    val id: Long,
    val name: String,
    val apps: List<AppItem>,
    override val page: Int,
    override val cellX: Int,
    override val cellY: Int,
    /** The icon the user picked for the folder, shown in place of its preview; null = the preview. */
    val icon: CustomIcon? = null,
) : HomeEntry

/** A pinned deep shortcut placed at a free cell ([rowId] is the home_items row, used to move/remove).
 *  [enabled] is false while the app has it disabled ([disabledMessage] is the app's reason, if any). */
data class PlacedShortcut(
    val rowId: Long,
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
    val label: String,
    val icon: ImageBitmap?,
    val enabled: Boolean,
    val disabledMessage: String?,
    override val page: Int,
    override val cellX: Int,
    override val cellY: Int,
) : HomeEntry

/** A bound app widget placed at a home cell, occupying [spanX]×[spanY] cells. */
data class PlacedWidget(
    val rowId: Long,
    val appWidgetId: Int,
    val provider: ComponentName,
    override val page: Int,
    override val cellX: Int,
    override val cellY: Int,
    val spanX: Int,
    val spanY: Int,
) : HomeEntry

/**
 * A restored widget whose device-local `appWidgetId` isn't bound on this device yet. Rendered as a
 * tap-to-set-up placeholder occupying [spanX]×[spanY] cells until the user re-binds it (see the restore
 * flow in HomeScreen). Its row already carries the [provider] and spans from the backup.
 */
data class PendingWidget(
    val rowId: Long,
    val provider: ComponentName,
    override val page: Int,
    override val cellX: Int,
    override val cellY: Int,
    val spanX: Int,
    val spanY: Int,
) : HomeEntry

/** A built-in launcher widget ([type] — smartspace or notifications), occupying [spanX]×[spanY]. */
data class PlacedBuiltin(
    val rowId: Long,
    val type: String,
    override val page: Int,
    override val cellX: Int,
    override val cellY: Int,
    val spanX: Int,
    val spanY: Int,
) : HomeEntry

/** A dock slot's content: an app or a folder, [rowId] being its home_items row (the dock's order key). */
sealed interface DockEntry {
    val rowId: Long
}

/** An app in a dock slot. */
data class DockApp(override val rowId: Long, val app: AppItem) : DockEntry

/** A folder in a dock slot: [folder] at page [DOCK_PAGE], cellX = the slot, so the same card,
 *  icon and rename paths serve it as a home folder. */
data class DockFolder(val folder: PlacedFolder) : DockEntry {
    override val rowId: Long get() = folder.id
}

/** The [PlacedFolder.page] of a folder that sits in the dock, not on a home page. */
const val DOCK_PAGE = -1

data class HomeUiState(
    val settings: LauncherSettings = LauncherSettings(),
    /** The dock's slots in order, cut to the dock's columns (rows past them are a hidden tail). */
    val dockEntries: List<DockEntry> = emptyList(),
    val entries: List<HomeEntry> = emptyList(),
    val pageCount: Int = 1,
    /** The page HOME returns to, clamped to the pages that exist. */
    val homePage: Int = 0,
    /** Pages with any stored row on them — including rows that resolved to nothing (an uninstalled
     *  shortcut's), which [entries] leaves out but the page menu must still treat as occupied. */
    val occupiedPages: Set<Int> = emptySet(),
    val badges: Map<String, Int> = emptyMap(),
    /** False for the placeholder shown before settings and the layout have been read. */
    val loaded: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val appRepository: AppRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    notificationBadgeRepository: NotificationBadgeRepository,
    private val signalMonitor: org.arkikeskus.launcher.launcher.system.SignalMonitor,
    private val customIcons: CustomIconStore,
) : ViewModel() {

    private val _notices = Channel<HomeNotice>(Channel.BUFFERED)

    /** One-shot notices for the screen to show, each delivered once (see [HomeNotice]). */
    val notices: Flow<HomeNotice> = _notices.receiveAsFlow()

    /** True while the first-run intro should cover the home screen (fresh installs only). */
    private val _showOnboarding = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showOnboarding: StateFlow<Boolean> = _showOnboarding

    // Declared BEFORE init on purpose: viewModelScope is Main.immediate and the ViewModel is built on
    // the main thread, so the collectors in init run synchronously inside the constructor up to their
    // first suspension. A shortcut batch that pooled in the repository while no ViewModel existed is
    // handed over right there, and anything declared after init would still be null at that point.

    /** Resolved pinned shortcuts: filled from the uiState combine, invalidated by the collectors in init. */
    private val shortcutCache = PinnedShortcutCache()

    /** (package, profile serial) of every shortcut pinned on home, for skipping irrelevant shortcut events. */
    private val pinnedShortcutOwners: StateFlow<Set<Pair<String, Long>>> = homeLayoutRepository.homeItems
        .map { items ->
            items.filter { it.containerId == HomeItemEntity.HOME && it.isShortcut }.mapTo(HashSet()) { it.packageName to it.userSerial }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // First-run experience, decided ONCE per install: on an empty home surface, seed the
        // smartspace widget + the default dock (phone/messages/Settings/browser/camera) and show
        // the intro. Device-local flags so removing items never re-seeds, and updating users
        // (non-empty home) get the flags silently without any of it.
        viewModelScope.launch {
            // Versions before 0.9.7 kept the dock as a favourites key list: rows first, the key
            // only once they exist (a death in between re-runs this, and the rows are skipped).
            val legacyDock = settingsRepository.legacyDockFavorites()
            if (legacyDock.isNotEmpty()) {
                homeLayoutRepository.migrateDockFavorites(legacyDock)
                settingsRepository.clearLegacyDockFavorites()
            }
            val seeded = settingsRepository.defaultLayoutSeededOnce()
            val onboarded = settingsRepository.onboardingDoneOnce()
            if (seeded && onboarded) return@launch
            // Decide freshness ONCE and persist the decision BEFORE any seeding writes: a death
            // mid-seed leaves the home non-empty, which must not flip a fresh install into an
            // "updating user" on the retry (dock never seeded, intro never shown).
            val fresh = settingsRepository.firstRunFreshOnce() ?: run {
                val decided = homeLayoutRepository.isHomeEmpty()
                settingsRepository.setFirstRunFresh(decided)
                decided
            }
            if (!seeded) {
                if (fresh) {
                    val settings = settingsRepository.settings.first()
                    // Idempotent on a retry: the widget only while home is still empty, the dock
                    // only while the favorites are still empty.
                    if (homeLayoutRepository.isHomeEmpty()) {
                        // Full width so the centered clock sits in the middle of the screen.
                        homeLayoutRepository.addBuiltin(
                            HomeItemEntity.BUILTIN_SMARTSPACE,
                            settings.homeColumns, SMARTSPACE_DEFAULT_SPAN_Y, settings.homeColumns, settings.homeRows,
                        )
                    }
                    // The app list needs a beat on a cold start; a fresh device always has apps.
                    val apps = appRepository.apps.first { it.isNotEmpty() }
                    val dockKeys = resolveDefaultDockKeys(context, apps)
                    if (dockKeys.isNotEmpty() && homeLayoutRepository.isDockEmpty()) {
                        val byKey = apps.associateBy { it.key }
                        homeLayoutRepository.seedDock(dockKeys.mapNotNull { byKey[it] })
                        // The seed is 5 apps but the dock defaults to 4 columns — widen to fit.
                        if (dockKeys.size > settings.dockColumns) {
                            settingsRepository.setDockColumns(dockKeys.size)
                        }
                    }
                }
                settingsRepository.setDefaultLayoutSeeded()
            }
            if (!onboarded) {
                if (fresh) _showOnboarding.value = true else settingsRepository.setOnboardingDone()
            }
        }
        // A package update can change a pinned shortcut's label/icon; drop its cached resolutions
        // so the uiState combine re-resolves them. Batches: the events of a burst pool up in the
        // repository instead of overflowing a buffer.
        viewModelScope.launch {
            appRepository.packageEvents.collect { packages -> packages.forEach(shortcutCache::invalidate) }
        }
        // So can the app itself at any time (a new name or photo, disabling or removing one), as
        // Launcher3's ShortcutsChangedTask handles. Most of these events come from apps with nothing
        // pinned on home (chat apps re-publish on every message) and are skipped. The rest arrive in
        // batches: the lock below is held through a whole restore, and the changes of that time
        // pool up in the repository instead of overflowing a buffer.
        viewModelScope.launch {
            appRepository.shortcutChanges.collect { owners ->
                val onHome = shortcutCache.shortcutsChanged(owners, pinnedShortcutOwners.value)
                if (onHome.isEmpty()) return@collect
                ShortcutPinLock.withLock {
                    onHome.forEach { homeLayoutRepository.removeMissingShortcuts(it.first, it.second) }
                }
            }
        }
        // Ghost-row hygiene: an uninstalled app's home rows never render but still occupy their
        // cells, silently blocking placement/resizes/moves with no visible reason. Delete them on
        // the uninstall event, and sweep once per launch for uninstalls the launcher slept through.
        // A removal may be handled late — it pools for a ViewModel created later, after the activity
        // was destroyed behind Settings — and by then the app can be back: reinstalled, with a
        // shortcut pinned through the confirmation, which runs without a ViewModel. So rows go only
        // while the app is still gone; the check is fail-safe (an app it cannot judge keeps its rows).
        // The dock row goes with the home rows (Launcher3 drops hotseat items on uninstall too): a
        // slot kept for a gone app showed empty, the user filled it, and when the app came back the
        // dock ran past the column cap: the last icon fell off the dock, and off home.
        viewModelScope.launch {
            appRepository.packageRemovals.collect { removed ->
                removed.forEach { (pkg, serial) ->
                    val stillGone = withContext(Dispatchers.IO) { !appRepository.isAppInstalled(pkg, serial) }
                    if (stillGone) homeLayoutRepository.removeAppRowsForPackage(pkg, serial)
                }
            }
        }
        viewModelScope.launch {
            appRepository.apps.first { it.isNotEmpty() } // let the app providers settle on a cold start
            homeLayoutRepository.removeStaleAppRows(appRepository::isAppInstalled)
            appRepository.followRenamedActivitiesInSettings()
        }
    }

    /** Closes the first-run intro (finished or skipped) and never shows it again. */
    fun finishOnboarding() {
        _showOnboarding.value = false
        viewModelScope.launch { settingsRepository.setOnboardingDone() }
    }

    /** Contacts granted in the intro → also enable the drawer's contact search (its whole point). */
    fun onContactsPermissionGranted() =
        viewModelScope.launch { settingsRepository.setSearchContacts(true) }

    /** Phone-state granted in the intro → restart the telephony callbacks so the network
     *  generation label appears without waiting for a process restart. */
    fun onPhonePermissionGranted() = signalMonitor.onPermissionsChanged()

    /** Called on every home resume: catches a READ_PHONE_STATE grant made outside onboarding
     *  (Settings ▸ Luvat, system app info), which has no callback into the monitor. Cheap no-op
     *  when the grant state hasn't changed. */
    fun refreshPhonePermission() = signalMonitor.refreshPermission()

    /**
     * True for a short window after a heads-up-worthy notification is posted, while the system
     * transiently reveals its own status bar over the launcher. HomeScreen blanks the themed status bar
     * during it so the two don't overlap (the reveal is not dispatched as a WindowInsets change, so this
     * NotificationListener-fed signal is the only way to detect it).
     */
    val headsUpActive: StateFlow<Boolean> = notificationBadgeRepository.headsUpActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        appRepository.apps,
        // A new cache generation re-runs this with the same rows, re-resolving what was invalidated.
        homeLayoutRepository.homeItems.combine(shortcutCache.generation) { items, _ -> items }
            .combine(settingsRepository.folderIcons) { items, icons -> items to icons },
        notificationBadgeRepository.badges,
    ) { settings, apps, (homeItems, folderIcons), badges ->
        val byKey = apps.associateBy { it.key }
        // Children grouped by their folder id, kept in stored order.
        val childrenByFolder = homeItems
            .filter { it.containerId != HomeItemEntity.HOME && it.containerId != HomeItemEntity.DOCK }
            .groupBy { it.containerId }
        // distinctBy: a duplicate child row (old data from before the merge fix, or an edited
        // backup) must never reach the folder grid's app-key lazy keys.
        fun folderApps(folderId: Long) = childrenByFolder[folderId].orEmpty().mapNotNull { byKey[it.key] }.distinctBy { it.key }
        // The dock: its rows in slot order, an app that no longer resolves skipped, cut to the columns.
        val dockEntries = homeItems
            .filter { it.containerId == HomeItemEntity.DOCK }
            .sortedWith(compareBy({ it.page }, { it.cellY }, { it.cellX }))
            .mapNotNull { row ->
                when {
                    row.isFolder -> DockFolder(
                        PlacedFolder(
                            row.id, row.folderName.orEmpty(), folderApps(row.id), DOCK_PAGE, row.cellX, 0,
                            icon = folderIcons[FolderIconKey.home(row.id)],
                        ),
                    )
                    row.isApp -> byKey[row.key]?.let { DockApp(row.id, it) }
                    else -> null
                }
            }
            .take(settings.dockColumns)
        // Resolve any not-yet-cached pinned shortcuts (label, icon, state) off the main thread. This
        // pass shows what it resolved even when a racing change kept it out of the cache — the retry
        // pass that the cache requested then replaces it.
        val resolvedShortcuts = HashMap<PinnedShortcutCache.Key, AppShortcuts.Resolved>()
        for (row in homeItems) {
            if (row.containerId == HomeItemEntity.HOME && row.isShortcut) {
                val key = PinnedShortcutCache.Key(row.packageName, row.shortcutId!!, row.userSerial)
                val resolved = shortcutCache[key] ?: run {
                    val epoch = shortcutCache.epoch(row.packageName)
                    withContext(Dispatchers.IO) {
                        AppShortcuts.resolve(context, row.packageName, key.shortcutId, row.userSerial)
                    }?.also { shortcutCache.put(key, it, epoch) }
                }
                if (resolved != null) resolvedShortcuts[key] = resolved
            }
        }
        val entries = homeItems
            .filter { it.containerId == HomeItemEntity.HOME }
            .mapNotNull { row ->
                when {
                    row.isBuiltin -> PlacedBuiltin(
                        rowId = row.id,
                        type = row.builtinType!!,
                        page = row.page, cellX = row.cellX, cellY = row.cellY,
                        spanX = row.spanX, spanY = row.spanY,
                    )
                    row.isFolder -> PlacedFolder(
                        row.id, row.folderName.orEmpty(), folderApps(row.id), row.page, row.cellX, row.cellY,
                        icon = folderIcons[FolderIconKey.home(row.id)],
                    )
                    row.isShortcut -> {
                        resolvedShortcuts[PinnedShortcutCache.Key(row.packageName, row.shortcutId!!, row.userSerial)]?.let { r ->
                            PlacedShortcut(
                                rowId = row.id,
                                packageName = row.packageName,
                                shortcutId = row.shortcutId!!,
                                userSerial = row.userSerial,
                                label = r.label,
                                icon = r.icon,
                                enabled = r.enabled,
                                disabledMessage = r.disabledMessage,
                                page = row.page,
                                cellX = row.cellX,
                                cellY = row.cellY,
                            )
                        }
                    }
                    row.isWidget -> {
                        ComponentName.unflattenFromString(row.widgetProvider.orEmpty())?.let { provider ->
                            PlacedWidget(
                                rowId = row.id,
                                appWidgetId = row.appWidgetId!!,
                                provider = provider,
                                page = row.page, cellX = row.cellX, cellY = row.cellY,
                                spanX = row.spanX, spanY = row.spanY,
                            )
                        }
                    }
                    else -> {
                        // A restored widget row carries a provider but no bound id → placeholder until
                        // re-bound; anything else is a plain app. (Local val so the null-check smart-casts
                        // — widgetProvider is a cross-module property that can't be smart-cast directly.)
                        val wp = row.widgetProvider
                        if (wp != null) {
                            ComponentName.unflattenFromString(wp)?.let { provider ->
                                PendingWidget(
                                    rowId = row.id,
                                    provider = provider,
                                    page = row.page, cellX = row.cellX, cellY = row.cellY,
                                    spanX = row.spanX, spanY = row.spanY,
                                )
                            }
                        } else {
                            byKey[row.key]?.let { PlacedApp(it, row.page, row.cellX, row.cellY) }
                        }
                    }
                }
            }
        val occupied = homeItems.filter { it.containerId == HomeItemEntity.HOME }.map { it.page }.toSet()
        // A new trailing page is offered transiently by the workspace while dragging, and becomes
        // permanent once an icon lands.
        val pageCount = permanentPageCount(occupied, settings.homePageCount)
        HomeUiState(
            settings = settings,
            dockEntries = dockEntries,
            entries = entries,
            pageCount = pageCount,
            homePage = settings.homePage.coerceIn(0, pageCount - 1),
            occupiedPages = occupied,
            badges = badges,
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    /** Launches [appItem], with a zoom from its icon when [from] says where it was tapped. */
    fun launch(appItem: AppItem, from: AppLaunch? = null) {
        appRepository.launch(appItem, from?.sourceBounds, from?.options).onFailure {
            Log.w("HomeViewModel", "Failed to launch ${appItem.key}", it)
        }
    }

    /** The left-edge swipe is armed only while its app still resolves: an uninstalled target left the
     *  gesture swallowing right-drags on the first page with nothing to open. */
    val leftSwipeAvailable: StateFlow<Boolean> =
        combine(settingsRepository.settings, appRepository.apps) { s, apps ->
            s.leftSwipeAppKey.isNotBlank() && apps.any { it.key == s.leftSwipeAppKey }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /**
     * Launches the app bound to the home left-edge swipe (Settings ▸ Eleet ▸ Vasen reuna). No-op when
     * none is configured (blank key) or the app no longer resolves (e.g. uninstalled).
     */
    fun onLeftSwipe() = viewModelScope.launch {
        val key = settingsRepository.settings.first().leftSwipeAppKey
        if (key.isBlank()) return@launch
        appRepository.apps.first().firstOrNull { it.key == key }?.let { launch(it) }
    }

    /** "Not now" on the set-as-default card: hide it on this device from now on. */
    fun dismissDefaultReminder() {
        viewModelScope.launch { settingsRepository.dismissDefaultReminder() }
    }

    /** Launches a pinned deep shortcut placed on the home screen (a disabled one explains itself). */
    fun launchShortcut(shortcut: PlacedShortcut) = AppShortcuts.startById(
        context, shortcut.packageName, shortcut.shortcutId, shortcut.userSerial, shortcut.enabled, shortcut.disabledMessage,
    )

    /** Stores a pinned shortcut on home; false when every page the pager shows is full. */
    private suspend fun storePinnedShortcut(packageName: String, shortcutId: String, userSerial: Long): Boolean {
        val s = settingsRepository.settings.first()
        return homeLayoutRepository.addShortcut(packageName, shortcutId, userSerial, s.homeColumns, s.homeRows)
    }

    /** Removes a folder together with its apps — offered only for a folder that shows no apps (all
     *  of them disabled or gone), which could otherwise never leave its cell. */
    fun removeFolder(folderId: Long) = viewModelScope.launch {
        // Re-checked against the current state: its apps may have come back since the drag began.
        val state = uiState.value
        val folder = state.entries.filterIsInstance<PlacedFolder>().firstOrNull { it.id == folderId }
            ?: state.dockEntries.filterIsInstance<DockFolder>().firstOrNull { it.rowId == folderId }?.folder
        if (folder != null && folder.apps.isEmpty()) homeLayoutRepository.removeFolder(folderId)
    }

    /** The folder card's "Delete folder" (issue #20), confirmed there: the folder goes with every app
     *  in it, which stay in the drawer. [removeFolder] above is the drag path's, for an empty folder only. */
    fun deleteFolder(folderId: Long) = viewModelScope.launch { homeLayoutRepository.removeFolder(folderId) }

    /** Removes a pinned shortcut from home and re-pins the remaining set for its package in the system. */
    fun removeShortcut(rowId: Long) = viewModelScope.launch {
        ShortcutPinLock.withLock {
            homeLayoutRepository.removeShortcut(rowId)?.let { remaining ->
                AppShortcuts.setPinned(context, remaining.packageName, remaining.userSerial, remaining.shortcutIds)
            }
        }
    }

    /** Pins [item] in the system (IO — the Binder round-trips must not run on the main thread) and
     *  places it on the home grid only if the system pin succeeded (else it would be a dead cell).
     *  When home has no room for it the system pin is released again — re-pinned to the ids home
     *  shows, as [removeShortcut] does — so the system never holds a shortcut home cannot show. */
    fun pinShortcut(item: AppShortcuts.Item) = viewModelScope.launch {
        ShortcutPinLock.withLock {
            if (!AppShortcuts.pin(context, item)) return@withLock
            if (!storePinnedShortcut(item.packageName, item.id, item.userSerial)) {
                val remaining = homeLayoutRepository.homeShortcutIds(item.packageName, item.userSerial)
                AppShortcuts.setPinned(context, item.packageName, item.userSerial, remaining)
                notify(HomeNotice.HomeFull)
            }
        }
    }

    suspend fun addWidgetAt(
        appWidgetId: Int?, provider: String?, builtinType: String?,
        placement: org.arkikeskus.launcher.model.WidgetPlacement,
    ): Boolean {
        val s = settingsRepository.settings.first()
        return homeLayoutRepository.addWidgetAt(
            appWidgetId, provider, builtinType, placement, s.homeColumns, s.homeRows,
        )
    }

    // --- Pages (the empty-area menu) -----------------------------------------------------------

    private val pageOperations = WorkspacePageOperations(
        settings = { settingsRepository.settings.first() },
        occupiedPages = {
            homeLayoutRepository.homeItems.first().filter { it.containerId == HomeItemEntity.HOME }
                .map { it.page }.toSet()
        },
        insertRows = homeLayoutRepository::insertPage,
        removeRows = homeLayoutRepository::removeEmptyPage,
        save = settingsRepository::setHomePages,
    )

    /** Opens an empty page at [at] (0..pageCount); what was there and after moves right. Returns the
     *  new index, -1 at the page limit, or [PAGE_FAILED] when storage refused. */
    suspend fun insertPage(at: Int): Int = pageOperation { pageOperations.insert(at) } ?: PAGE_FAILED

    /** HOME returns to [page] from now on. */
    fun setHomePage(page: Int) = viewModelScope.launch { pageOperations.setHome(page) }

    /** True when the home screen shows nothing on [page]. A row can still be stored there (a
     *  disabled app, a shortcut its app dropped, a widget without a provider); such leftovers are
     *  purged when the user removes the page, or the page could never be removed. */
    fun isPageEmpty(page: Int): Boolean = uiState.value.entries.none { it.page == page }

    /** Removes an empty page, returning its navigation destination, null when rejected (not empty,
     *  the only page), or [PAGE_FAILED] when storage refused. */
    suspend fun removeEmptyPage(page: Int): Int? =
        pageOperation { pageOperations.remove(page, purge = isPageEmpty(page)) ?: return null } ?: PAGE_FAILED

    private suspend inline fun pageOperation(block: () -> Int): Int? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("HomeViewModel", "page operation failed", e)
        null
    }

    /** Removes a placed widget row (caller frees the host id). */
    fun removeWidget(rowId: Long) = viewModelScope.launch { homeLayoutRepository.removeWidget(rowId) }

    /** Turns bound widget rows whose id this device's host never allocated back into placeholders
     *  (the Google Auto Backup / device-transfer restore path — see HomeScreen's startup sweep). */
    suspend fun unbindStaleWidgets(validIds: Set<Int>) = homeLayoutRepository.unbindStaleWidgets(validIds)

    /** Binds a restored placeholder widget to its freshly allocated [appWidgetId] (the caller did the
     *  allocate + system bind/configure); the row turns back into a live widget. */
    suspend fun bindRestoredWidget(rowId: Long, appWidgetId: Int): Boolean =
        homeLayoutRepository.bindRestoredWidget(rowId, appWidgetId)

    /** Device-local ids of all bound widgets (for the startup AppWidgetHost id reconcile). */
    suspend fun boundWidgetIds(): Set<Int> = homeLayoutRepository.boundWidgetIds()

    /** The dock's shown slots in their new order; rows the column cap hides keep their place after them. */
    fun reorderDock(newOrder: List<DockEntry>) =
        viewModelScope.launch { homeLayoutRepository.reorderDock(newOrder.map { it.rowId }) }

    fun removeFromHome(appItem: AppItem) =
        viewModelScope.launch { homeLayoutRepository.removeFromHome(appItem) }

    fun addToDock(appItem: AppItem) =
        viewModelScope.launch { homeLayoutRepository.addToDock(appItem) }

    /** A drawer icon dropped between dock tiles at [index] (counted over the shown slots). */
    fun addToDockAt(appItem: AppItem, index: Int) =
        viewModelScope.launch { homeLayoutRepository.addToDockAt(appItem, index, shownDockKeys()) }

    fun removeFromDock(appItem: AppItem) =
        viewModelScope.launch { homeLayoutRepository.removeFromDock(appItem) }

    /** The keys the dock shows (its rows that resolve), for a drop index counted over shown slots. */
    private suspend fun shownDockKeys(): Set<String> = appRepository.apps.first().mapTo(HashSet()) { it.key }

    /** The home_items container the dragged app's row is in, for a drop onto a dock app or folder:
     *  null for the drawer (no row). [openFolderId] is the folder whose card the drag left, if any. */
    fun dockDropContainer(source: DragSource, openFolderId: Long?): Long? = when (source) {
        DragSource.Home -> HomeItemEntity.HOME
        DragSource.Dock -> HomeItemEntity.DOCK
        DragSource.Folder -> openFolderId
        DragSource.Drawer -> null
    }

    /** [dropped] dropped onto the dock app [target]: the slot becomes a folder of the two. */
    fun createDockFolder(target: AppItem, dropped: AppItem, fromContainer: Long?, name: String) =
        viewModelScope.launch {
            withContext(NonCancellable) { homeLayoutRepository.createDockFolder(target, dropped, fromContainer, name) }
        }

    /** [appItem] dropped onto the dock folder [folderId]: joins it. */
    fun addToDockFolder(appItem: AppItem, folderId: Long, fromContainer: Long?) = viewModelScope.launch {
        withContext(NonCancellable) { homeLayoutRepository.addToDockFolder(appItem, folderId, fromContainer) }
    }

    /** A home folder dropped between dock tiles at [index]. */
    fun moveFolderToDock(folderId: Long, index: Int) = viewModelScope.launch {
        withContext(NonCancellable) { homeLayoutRepository.moveFolderToDock(folderId, index, shownDockKeys()) }
    }

    /** A dock folder dropped on a home cell; false (nothing changed) unless the cell is free. */
    suspend fun moveDockFolderToHome(folderId: Long, page: Int, cellX: Int, cellY: Int): Boolean =
        homeLayoutRepository.moveDockFolderToHome(folderId, page, cellX, cellY)

    fun addToHome(appItem: AppItem) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        if (!homeLayoutRepository.addToHome(appItem, s.homeColumns, s.homeRows)) notify(HomeNotice.HomeFull)
    }

    /** Queues a notice for the screen; the buffer absorbs a burst while nothing collects yet. */
    private fun notify(notice: HomeNotice) {
        _notices.trySend(notice)
    }

    /** Moves/swaps a home shortcut; returns whether the repository accepted it (see [Workspace]). */
    suspend fun moveItem(appItem: AppItem, page: Int, cellX: Int, cellY: Int): Boolean =
        homeLayoutRepository.moveItem(appItem, page, cellX, cellY)

    /** Moves/swaps a folder to a home cell (folder relocation on the grid). */
    suspend fun moveFolder(folderId: Long, page: Int, cellX: Int, cellY: Int): Boolean =
        homeLayoutRepository.moveFolder(folderId, page, cellX, cellY)

    /** Moves or resizes a widget to the given bounds; returns whether the repository accepted it
     *  (the Workspace clears its optimistic override on false). */
    suspend fun setWidgetBounds(rowId: Long, page: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int): Boolean {
        val s = settingsRepository.settings.first()
        return homeLayoutRepository.setWidgetBounds(rowId, page, cellX, cellY, spanX, spanY, s.homeColumns, s.homeRows)
    }

    /** Cross-surface: an icon dragged from the dock onto a home cell — place it (an app there makes
     *  a folder named [folderName], a folder there takes it in) and leave the dock. One transaction;
     *  NonCancellable so a ViewModel clear mid-way can't strand the item on both surfaces. */
    fun moveToHome(appItem: AppItem, page: Int, cellX: Int, cellY: Int, folderName: String) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        withContext(NonCancellable) {
            when (homeLayoutRepository.moveDockAppToHome(appItem, page, cellX, cellY, s.homeColumns, s.homeRows, folderName)) {
                PlaceResult.Placed -> Unit
                PlaceResult.HomeFull -> notify(HomeNotice.HomeFull)
                PlaceResult.Blocked -> Unit // the icon snaps back to the dock
            }
        }
    }

    /** Cross-surface: a home icon dragged into the dock at [index] — its row moves to the dock. */
    fun moveToDock(appItem: AppItem, index: Int) = viewModelScope.launch {
        withContext(NonCancellable) {
            // [index] counts the dock as shown; rows that don't resolve are skipped in it.
            homeLayoutRepository.moveHomeAppToDock(appItem, index, shownDockKeys())
        }
    }

    /** Drop an app onto another home app → make a folder of the two. */
    fun createFolder(target: AppItem, dropped: AppItem, name: String) = viewModelScope.launch {
        homeLayoutRepository.createFolder(target, dropped, name)
    }

    /** Drop an app onto an existing folder → add it to that folder. */
    fun addToFolder(appItem: AppItem, folderId: Long) = viewModelScope.launch {
        homeLayoutRepository.addToFolder(appItem, folderId)
    }

    /** Take an app out of a folder back onto the home screen (dissolves the folder if one is left). */
    fun removeFromFolder(appItem: AppItem, folderId: Long) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        if (!homeLayoutRepository.removeFromFolder(appItem, folderId, s.homeColumns, s.homeRows)) {
            notify(HomeNotice.HomeFull)
        }
    }

    /** A drag inside the open folder ended at a new slot: [appItem] goes just before [before]
     *  (null = last). */
    fun reorderInFolder(folderId: Long, appItem: AppItem, before: AppItem?) = viewModelScope.launch {
        homeLayoutRepository.moveWithinFolder(folderId, appItem, before)
    }

    /** Cross-surface: an app dragged out of its open folder onto a home cell — placed with the same
     *  rules as a drawer drop (an app there makes a folder named [folderName], a folder there takes
     *  it in); a full home reports [HomeNotice.HomeFull] and leaves the app in the folder. */
    fun moveOutOfFolder(appItem: AppItem, folderId: Long, page: Int, cellX: Int, cellY: Int, folderName: String) =
        viewModelScope.launch {
            val s = settingsRepository.settings.first()
            withContext(NonCancellable) {
                val result = homeLayoutRepository.moveOutOfFolderTo(
                    appItem, folderId, page, cellX, cellY, s.homeColumns, s.homeRows, folderName,
                )
                if (result == PlaceResult.HomeFull) notify(HomeNotice.HomeFull)
            }
        }

    /** Cross-surface: an app dragged out of its open folder into the dock at [index] (null = after
     *  the last slot) — its child row becomes a dock row (one transaction; NonCancellable so a
     *  ViewModel clear can't strand it). */
    fun moveFolderAppToDock(appItem: AppItem, folderId: Long, index: Int? = null) = viewModelScope.launch {
        withContext(NonCancellable) { homeLayoutRepository.moveChildToDock(appItem, folderId, index, shownDockKeys()) }
    }

    /** An app dragged out of its open folder onto the remove pill: it leaves the home screen entirely. */
    fun removeFromFolderEntirely(appItem: AppItem, folderId: Long) = viewModelScope.launch {
        homeLayoutRepository.removeChild(appItem, folderId)
    }

    fun renameFolder(folderId: Long, name: String) =
        viewModelScope.launch { homeLayoutRepository.renameFolder(folderId, name) }

    /** The icon picked for a home folder (null = back to its preview). */
    fun setFolderIcon(folderId: Long, icon: CustomIcon?) =
        viewModelScope.launch { customIcons.setFolderIcon(FolderIconKey.home(folderId), icon) }

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

    /** Sets a custom display name for an app (blank/null clears it back to the system label). */
    fun setCustomLabel(key: String, label: String?) =
        viewModelScope.launch { settingsRepository.setCustomLabel(key, label) }
}
