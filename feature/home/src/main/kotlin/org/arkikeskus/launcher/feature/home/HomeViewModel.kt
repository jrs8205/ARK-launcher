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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.AppShortcuts
import javax.inject.Inject

/** How long the themed status bar stays blanked after a heads-up post; SystemUI auto-dismisses a
 *  heads-up notification after ~5 s (its default decay) plus a hide animation, and we have no
 *  un-pin signal, so we time it out past that. 6 s left the system bar's dismissal animation
 *  overlapping our re-shown bar for ~1 s on a real WhatsApp heads-up (Pixel 8a, Android 17). */
private const val HEADS_UP_SUPPRESS_MS = 8_000L

/** Default grid footprint of the built-in smartspace widget (clamped to the column count). */
const val SMARTSPACE_DEFAULT_SPAN_X = 4
const val SMARTSPACE_DEFAULT_SPAN_Y = 2

/** Smallest allowed smartspace size — below 3 columns the clock + event row no longer fit. */
const val SMARTSPACE_MIN_SPAN_X = 3

/** Edit-frame default width of the built-in notifications widget (the picker ADDS it full-width —
 *  a narrower footprint can't center its icon group on an odd column count). */
const val NOTIFICATIONS_DEFAULT_SPAN_X = 4
const val NOTIFICATIONS_DEFAULT_SPAN_Y = 1

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
) : HomeEntry

/** A pinned deep shortcut placed at a free cell ([rowId] is the home_items row, used to move/remove). */
data class PlacedShortcut(
    val rowId: Long,
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
    val label: String,
    val icon: ImageBitmap?,
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

data class HomeUiState(
    val settings: LauncherSettings = LauncherSettings(),
    val dockApps: List<AppItem> = emptyList(),
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
) : ViewModel() {

    /** True while the first-run intro should cover the home screen (fresh installs only). */
    private val _showOnboarding = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showOnboarding: StateFlow<Boolean> = _showOnboarding

    init {
        // First-run experience, decided ONCE per install: on an empty home surface, seed the
        // smartspace widget + the default dock (phone/messages/Settings/browser/camera) and show
        // the intro. Device-local flags so removing items never re-seeds, and updating users
        // (non-empty home) get the flags silently without any of it.
        viewModelScope.launch {
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
                    if (dockKeys.isNotEmpty() && settingsRepository.dockFavorites.first().isEmpty()) {
                        settingsRepository.seedDock(dockKeys)
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
        // so the uiState combine re-resolves them (cache keys are "package/id/serial").
        viewModelScope.launch {
            appRepository.packageEvents.collect { pkg ->
                shortcutCache.keys.removeIf { it.startsWith("$pkg/") }
            }
        }
        // Ghost-row hygiene: an uninstalled app's home rows never render but still occupy their
        // cells, silently blocking placement/resizes/moves with no visible reason. Delete them on
        // the uninstall event, and sweep once per launch for uninstalls the launcher slept through.
        viewModelScope.launch {
            appRepository.packageRemovals.collect { (pkg, serial) ->
                homeLayoutRepository.removeAppRowsForPackage(pkg, serial)
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

    /** Resolved (label + icon) cache for pinned shortcuts, keyed by package/id/userSerial.
     *  Concurrent: filled from the uiState combine, invalidated from the package-event collector. */
    private val shortcutCache = java.util.concurrent.ConcurrentHashMap<String, AppShortcuts.Resolved>()

    /**
     * True for a short window after a heads-up-worthy notification is posted, while the system
     * transiently reveals its own status bar over the launcher. HomeScreen blanks the themed status bar
     * during it so the two don't overlap (the reveal is not dispatched as a WindowInsets change, so this
     * NotificationListener-fed signal is the only way to detect it — see the Fable-5 audit note).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val headsUpActive: StateFlow<Boolean> = notificationBadgeRepository.headsUp
        .flatMapLatest { postedAt ->
            // Compute the window from the timestamp so a value replayed on re-subscription (returning
            // home) is already expired and never re-blanks the bar; only a still-fresh post keeps it on.
            val remaining = postedAt + HEADS_UP_SUPPRESS_MS - SystemClock.elapsedRealtime()
            if (postedAt == 0L || remaining <= 0L) flowOf(false)
            else flow { emit(true); delay(remaining); emit(false) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        settingsRepository.dockFavorites,
        appRepository.apps,
        homeLayoutRepository.homeItems,
        notificationBadgeRepository.badges,
    ) { settings, favoriteKeys, apps, homeItems, badges ->
        val byKey = apps.associateBy { it.key }
        val dockApps = favoriteKeys.mapNotNull { byKey[it] }.take(settings.dockColumns)
        // Children grouped by their folder id, kept in stored order.
        val childrenByFolder = homeItems
            .filter { it.containerId != HomeItemEntity.HOME }
            .groupBy { it.containerId }
        // Resolve any not-yet-cached pinned shortcuts (label + icon) off the main thread.
        for (row in homeItems) {
            if (row.containerId == HomeItemEntity.HOME && row.isShortcut) {
                val k = "${row.packageName}/${row.shortcutId}/${row.userSerial}"
                if (!shortcutCache.containsKey(k)) {
                    withContext(Dispatchers.IO) {
                        AppShortcuts.resolve(context, row.packageName, row.shortcutId!!, row.userSerial)
                    }?.let { shortcutCache[k] = it }
                }
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
                    row.isFolder -> {
                        // distinctBy: a duplicate child row (old data from before the merge fix, or
                        // an edited backup) must never reach the folder grid's app-key lazy keys.
                        val folderApps = childrenByFolder[row.id].orEmpty()
                            .mapNotNull { byKey[it.key] }
                            .distinctBy { it.key }
                        PlacedFolder(row.id, row.folderName.orEmpty(), folderApps, row.page, row.cellX, row.cellY)
                    }
                    row.isShortcut -> {
                        val k = "${row.packageName}/${row.shortcutId}/${row.userSerial}"
                        shortcutCache[k]?.let { r ->
                            PlacedShortcut(
                                rowId = row.id,
                                packageName = row.packageName,
                                shortcutId = row.shortcutId!!,
                                userSerial = row.userSerial,
                                label = r.label,
                                icon = r.icon,
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
            dockApps = dockApps,
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

    fun launch(appItem: AppItem) {
        appRepository.launch(appItem).onFailure {
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

    /** Launches a pinned deep shortcut placed on the home screen. */
    fun launchShortcut(shortcut: PlacedShortcut) =
        AppShortcuts.startById(context, shortcut.packageName, shortcut.shortcutId, shortcut.userSerial)

    /** Stores a pinned shortcut on home (the system-level pin is done by the caller, which has a
     *  Context). Idempotent — the repository skips one already present. */
    fun addPinnedShortcut(packageName: String, shortcutId: String, userSerial: Long) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        homeLayoutRepository.addShortcut(packageName, shortcutId, userSerial, s.homeColumns, s.homeRows)
    }

    /** Removes a folder together with its apps — offered only for a folder that shows no apps (all
     *  of them disabled or gone), which could otherwise never leave its cell. */
    fun removeFolder(folderId: Long) = viewModelScope.launch { homeLayoutRepository.removeFolder(folderId) }

    /** Removes a pinned shortcut from home and re-pins the remaining set for its package in the system. */
    fun removeShortcut(rowId: Long) = viewModelScope.launch {
        homeLayoutRepository.removeShortcut(rowId)?.let { remaining ->
            AppShortcuts.setPinned(context, remaining.packageName, remaining.userSerial, remaining.shortcutIds)
        }
    }

    /** Pins [item] in the system (IO — the Binder round-trips must not run on the main thread) and
     *  places it on the home grid only if the system pin succeeded (else it would be a dead cell). */
    fun pinShortcut(item: AppShortcuts.Item) = viewModelScope.launch {
        if (AppShortcuts.pin(context, item)) {
            addPinnedShortcut(item.packageName, item.id, item.userSerial)
        }
    }

    /** Places a freshly-bound widget on the home grid (spans are the picker's default). */
    fun addWidget(appWidgetId: Int, provider: String, spanX: Int, spanY: Int) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        homeLayoutRepository.addWidget(appWidgetId, provider, spanX, spanY, s.homeColumns, s.homeRows)
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

    /** Adds the built-in smartspace widget at the first free full-width rectangle (widget picker). */
    fun addSmartspace() = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        homeLayoutRepository.addBuiltin(
            HomeItemEntity.BUILTIN_SMARTSPACE,
            s.homeColumns, SMARTSPACE_DEFAULT_SPAN_Y, s.homeColumns, s.homeRows,
        )
    }

    /** Adds the built-in notifications widget at the first free full-width rectangle (widget picker).
     *  Full width like the smartspace clock: the icon group centers inside its own footprint, so any
     *  narrower default could never sit symmetric on an odd column count (e.g. 4 wide in 5 columns). */
    fun addNotificationsWidget() = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        homeLayoutRepository.addBuiltin(
            HomeItemEntity.BUILTIN_NOTIFICATIONS,
            s.homeColumns, NOTIFICATIONS_DEFAULT_SPAN_Y, s.homeColumns, s.homeRows,
        )
    }

    /** Adds the built-in battery widget (an app-icon-sized ring) at the first free cell (widget picker). */
    fun addBatteryWidget() = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        homeLayoutRepository.addBuiltin(
            HomeItemEntity.BUILTIN_BATTERY,
            BATTERY_SPAN, BATTERY_SPAN, s.homeColumns, s.homeRows,
        )
    }

    /** Turns bound widget rows whose id this device's host never allocated back into placeholders
     *  (the Google Auto Backup / device-transfer restore path — see HomeScreen's startup sweep). */
    suspend fun unbindStaleWidgets(validIds: Set<Int>) = homeLayoutRepository.unbindStaleWidgets(validIds)

    /** Binds a restored placeholder widget to its freshly allocated [appWidgetId] (the caller did the
     *  allocate + system bind/configure); the row turns back into a live widget. */
    suspend fun bindRestoredWidget(rowId: Long, appWidgetId: Int): Boolean =
        homeLayoutRepository.bindRestoredWidget(rowId, appWidgetId)

    /** Device-local ids of all bound widgets (for the startup AppWidgetHost id reconcile). */
    suspend fun boundWidgetIds(): Set<Int> = homeLayoutRepository.boundWidgetIds()

    fun reorderDock(newOrder: List<AppItem>) =
        viewModelScope.launch { settingsRepository.reorderVisibleDock(newOrder.map { it.key }) }

    fun removeFromHome(appItem: AppItem) =
        viewModelScope.launch { homeLayoutRepository.removeFromHome(appItem) }

    fun addToDock(appItem: AppItem) =
        viewModelScope.launch { settingsRepository.addToDock(appItem.key) }

    fun removeFromDock(appItem: AppItem) =
        viewModelScope.launch { settingsRepository.removeFromDock(appItem.key) }

    fun addToHome(appItem: AppItem) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        homeLayoutRepository.addToHome(appItem, s.homeColumns, s.homeRows)
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

    /** Cross-surface: an icon dragged from the dock onto a home cell — place it and leave the dock. */
    fun moveToHome(appItem: AppItem, page: Int, cellX: Int, cellY: Int) = viewModelScope.launch {
        val s = settingsRepository.settings.first()
        // Add-first so an interruption can only duplicate, never lose; NonCancellable so a
        // ViewModel clear between the halves can't strand the item on both surfaces.
        withContext(NonCancellable) {
            if (homeLayoutRepository.placeAt(appItem, page, cellX, cellY, s.homeColumns, s.homeRows)) {
                settingsRepository.removeFromDock(appItem.key)
            }
        }
    }

    /** Cross-surface: a home icon dragged into the dock at [index] — add to dock and leave home. */
    fun moveToDock(appItem: AppItem, index: Int) = viewModelScope.launch {
        withContext(NonCancellable) {
            // [index] counts the dock as shown; favourites that don't resolve are skipped in it.
            settingsRepository.addToDockAt(appItem.key, index, appRepository.apps.first().mapTo(HashSet()) { it.key })
            homeLayoutRepository.removeFromHome(appItem)
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
        homeLayoutRepository.removeFromFolder(appItem, folderId, s.homeColumns, s.homeRows)
    }

    fun renameFolder(folderId: Long, name: String) =
        viewModelScope.launch { homeLayoutRepository.renameFolder(folderId, name) }

    /** Sets a custom display name for an app (blank/null clears it back to the system label). */
    fun setCustomLabel(key: String, label: String?) =
        viewModelScope.launch { settingsRepository.setCustomLabel(key, label) }
}
