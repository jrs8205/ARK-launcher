package org.arkikeskus.launcher.feature.home

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

data class HomeUiState(
    val settings: LauncherSettings = LauncherSettings(),
    val dockApps: List<AppItem> = emptyList(),
    val entries: List<HomeEntry> = emptyList(),
    val pageCount: Int = 1,
    val badges: Map<String, Int> = emptyMap(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val appRepository: AppRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    notificationBadgeRepository: NotificationBadgeRepository,
) : ViewModel() {

    val rows: Int = HomeLayoutRepository.ROWS

    /** Resolved (label + icon) cache for pinned shortcuts, keyed by package/id/userSerial. */
    private val shortcutCache = mutableMapOf<String, AppShortcuts.Resolved>()

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
                    row.isFolder -> {
                        val folderApps = childrenByFolder[row.id].orEmpty().mapNotNull { byKey[it.key] }
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
        val maxPage = entries.maxOfOrNull { it.page } ?: 0
        // Only as many pages as actually have icons (min 1). A new trailing page is offered
        // transiently by the workspace while dragging, and becomes permanent once an icon lands.
        // Capped: a corrupt page value in the DB must never reach PageDots' non-lazy repeat().
        val pageCount = (maxPage + 1).coerceIn(1, HomeLayoutRepository.MAX_PAGES)
        HomeUiState(
            settings = settings,
            dockApps = dockApps,
            entries = entries,
            pageCount = pageCount,
            badges = badges,
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
        val columns = settingsRepository.settings.first().homeColumns
        homeLayoutRepository.addShortcut(packageName, shortcutId, userSerial, columns)
    }

    /** Removes a pinned shortcut from home and re-pins the remaining set for its package in the system. */
    fun removeShortcut(rowId: Long) = viewModelScope.launch {
        homeLayoutRepository.removeShortcut(rowId)?.let { remaining ->
            AppShortcuts.setPinned(context, remaining.packageName, remaining.userSerial, remaining.shortcutIds)
        }
    }

    /** Places a freshly-bound widget on the home grid (spans are the picker's default). */
    fun addWidget(appWidgetId: Int, provider: String, spanX: Int, spanY: Int) = viewModelScope.launch {
        val columns = settingsRepository.settings.first().homeColumns
        homeLayoutRepository.addWidget(appWidgetId, provider, spanX, spanY, columns)
    }

    /** Removes a placed widget row (caller frees the host id). */
    fun removeWidget(rowId: Long) = viewModelScope.launch { homeLayoutRepository.removeWidget(rowId) }

    /** Binds a restored placeholder widget to its freshly allocated [appWidgetId] (the caller did the
     *  allocate + system bind/configure); the row turns back into a live widget. */
    fun bindRestoredWidget(rowId: Long, appWidgetId: Int) =
        viewModelScope.launch { homeLayoutRepository.bindRestoredWidget(rowId, appWidgetId) }

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
        val columns = settingsRepository.settings.first().homeColumns
        homeLayoutRepository.addToHome(appItem, columns)
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
        val columns = settingsRepository.settings.first().homeColumns
        return homeLayoutRepository.setWidgetBounds(rowId, page, cellX, cellY, spanX, spanY, columns)
    }

    /** Cross-surface: an icon dragged from the dock onto a home cell — place it and leave the dock. */
    fun moveToHome(appItem: AppItem, page: Int, cellX: Int, cellY: Int) = viewModelScope.launch {
        val columns = settingsRepository.settings.first().homeColumns
        if (homeLayoutRepository.placeAt(appItem, page, cellX, cellY, columns)) {
            settingsRepository.removeFromDock(appItem.key)
        }
    }

    /** Cross-surface: a home icon dragged into the dock at [index] — add to dock and leave home. */
    fun moveToDock(appItem: AppItem, index: Int) = viewModelScope.launch {
        settingsRepository.addToDockAt(appItem.key, index)
        homeLayoutRepository.removeFromHome(appItem)
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
        val columns = settingsRepository.settings.first().homeColumns
        homeLayoutRepository.removeFromFolder(appItem, folderId, columns)
    }

    fun renameFolder(folderId: Long, name: String) =
        viewModelScope.launch { homeLayoutRepository.renameFolder(folderId, name) }

    /** Sets a custom display name for an app (blank/null clears it back to the system label). */
    fun setCustomLabel(key: String, label: String?) =
        viewModelScope.launch { settingsRepository.setCustomLabel(key, label) }
}
