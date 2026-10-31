package org.arkikeskus.launcher.feature.home

import android.appwidget.AppWidgetManager
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.drawToBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import org.arkikeskus.launcher.model.WidgetPlacement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.ReorderPlanner
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.ui.DockTarget
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.dragPointer
import org.arkikeskus.launcher.ui.IconPress
import org.arkikeskus.launcher.ui.IconPressOutcome
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.appLaunchTouch
import org.arkikeskus.launcher.ui.awaitIconPress
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.AppLabel
import org.arkikeskus.launcher.ui.component.LocalAppLabelLines
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.arkikeskus.launcher.ui.component.LocalIconScale
import org.arkikeskus.launcher.ui.component.iconSizeForCell
import org.arkikeskus.launcher.ui.component.labelBlockHeight
import org.arkikeskus.launcher.ui.component.labelFontFactor
import org.arkikeskus.launcher.ui.rememberAppLaunchOrigin
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt

/**
 * The paged home workspace: a [HorizontalPager] of cell grids with a floating drag overlay.
 *
 * Gestures are separated by location so they never fight each other:
 * - **Icon** (child node): tap launches; long-press lifts the icon (haptic tick) and the drag
 *   consumes the pointer immediately, so neither the pager nor the page swipes can steal it. Drop
 *   on a cell to move it (a second tick); drop without moving opens [onAppMenu]; drag to the screen
 *   edge flips the page.
 * - **Page background** (parent node, only reached when no icon is under the finger): a still
 *   long-press opens settings. (The drawer/notifications swipe-up was hoisted to HomeScreen's root —
 *   see Modifier.pixelHomeSwipe — so it wins over icons and the dock too.)
 * - **Pager**: horizontal swipes change pages (disabled while an icon is being dragged).
 *
 * [homeSignals] (HOME pressed / home gesture) exits widget edit mode; when the press happened while
 * the launcher was already foreground (the Boolean payload) it also scrolls back to the first page.
 */
@Composable
internal fun Workspace(
    pageCount: Int,
    columns: Int,
    rows: Int,
    entries: List<HomeEntry>,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    /** The name under a folder's tile (issue #17); [showLabels] off hides it too. */
    showFolderNames: Boolean = true,
    labelColor: Color = Color.White,
    showPageIndicator: Boolean,
    homePageMarker: String = LauncherSettings.MARKER_RING,
    /** Swiping on from the last page continues to the first, and back from the first to the last
     *  (see WorkspaceWrap.kt). Off: the last page bounces back from the empty drop page. */
    infiniteScroll: Boolean = false,
    /** The left-edge app takes a right-drag on page 0 (HomeScreen's root detector), so page 0 never
     *  wraps back to the last page while it is set. */
    leftEdgeActionEnabled: Boolean = false,
    locked: Boolean,
    homeSignals: Flow<Boolean>,
    /** The page HOME returns to and the launcher opens on; null until settings have been read. */
    homePage: Int? = null,
    /** Pages the host wants shown (after adding/removing a page from the menu). */
    pageRequests: Flow<Int> = emptyFlow(),
    dragController: HomeDragController,
    widgetDragController: WidgetDragController,
    onAddWidget: (WidgetChoice, WidgetPlacement) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onAppMenu: (AppItem, IntOffset, Boolean) -> Unit,
    onMove: suspend (AppItem, Int, Int, Int) -> Boolean,
    onMoveFolder: suspend (Long, Int, Int, Int) -> Boolean,
    /** A home icon released over the dock where it can land (see [HomeDragController.dockAccepts]). */
    onDropOnDock: (AppItem, DockTarget) -> Unit,
    onRemoveFromHome: (AppItem) -> Unit,
    /** A home folder released over the dock while it has room, at the slot index under the finger. */
    onMoveFolderToDock: (folderId: Long, index: Int) -> Unit = { _, _ -> },
    onOpenFolder: (PlacedFolder) -> Unit,
    onLaunchShortcut: (PlacedShortcut) -> Unit,
    onRemoveShortcut: (Long) -> Unit,
    onRemoveFolder: (Long) -> Unit = {},
    onCreateFolder: (target: AppItem, dropped: AppItem) -> Unit,
    onAddToFolder: (app: AppItem, folderId: Long) -> Unit,
    onEmptyAreaMenu: (IntOffset, Boolean) -> Unit,
    /** Widgets keep an icon's side margin inside their cells ([widgetInset]); off they fill the span. */
    widgetEdgesAlignWithIcons: Boolean = false,
    // Two clean taps on empty space within the platform double-tap timeout. The caller decides what
    // (if anything) happens — Workspace only reports the gesture.
    onEmptyAreaDoubleTap: () -> Unit = {},
    // Removes a widget-like row from the grid; [appWidgetId] is null for a built-in widget (no host id).
    onRemoveWidget: (rowId: Long, appWidgetId: Int?) -> Unit = { _, _ -> },
    // Tap a restored-widget placeholder to re-bind it; long-press to discard the placeholder row.
    onRestorePendingWidget: (PendingWidget) -> Unit = {},
    onRemovePendingWidget: (rowId: Long) -> Unit = {},
    onSetWidgetBounds: suspend (rowId: Long, page: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> Boolean =
        { _, _, _, _, _, _ -> false },
    onReconfigureWidget: (appWidgetId: Int) -> Unit = {},
    // Persistent, page-independent host views (created once in HomeScreen, kept alive so their
    // AppWidgetHost listener survives page scrolling — otherwise a collection widget's deferred row
    // update is dropped). Keyed by appWidgetId. [widgetScrollableById] mirrors per-id scrollability.
    widgetViews: Map<Int, android.appwidget.AppWidgetHostView> = emptyMap(),
    widgetScrollableById: Map<Int, Boolean> = emptyMap(),
    /** Bumped on a provider change, so a widget whose provider info was missing is looked up again. */
    widgetProviderGeneration: Int = 0,
    widgetSizeReporter: WidgetSizeReporter = remember { WidgetSizeReporter() },
    /** The app drawer is open (or sliding): the home screen is not at rest for [newItems]. */
    drawerOpen: Boolean = false,
    /** Rows other apps' pin requests added while home was away, to reveal when it comes back at rest. */
    newItems: NewHomeItems = NewHomeItems.shared,
    /** Where the grid's cell size is published for the rest of the process (a widget request's confirmation). */
    cellSize: HomeCellSize = HomeCellSize.shared,
    /** The chrome around the grid the cell is measured with ([WorkspaceChrome]); null when unknown. */
    chrome: WorkspaceChrome? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val windowHeightPx = LocalWindowInfo.current.containerSize.height

    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf<PlacedApp?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var editingWidget by remember { mutableStateOf<EditingItem?>(null) }
    // While the edit frame is open, the root swipe-up detector must yield (it bails on localGestureActive),
    // so a handle/scrim drag can never open the drawer. Reset when the frame closes.
    LaunchedEffect(editingWidget, widgetDragController.active) {
        dragController.localGestureActive = editingWidget != null || widgetDragController.active != null
    }
    var widgetOptimistic by remember { mutableStateOf<Pair<Long, WidgetBounds>?>(null) }

    // Relocation of a folder or pinned shortcut, kept local: neither travels to the dock/drawer, so
    // they don't use the shared cross-surface controller — Workspace owns the gesture, floating
    // preview and drop, and moves them by their home_items row id (folder.id / shortcut.rowId).
    var draggingLocal by remember { mutableStateOf<HomeEntry?>(null) }
    var localMoving by remember { mutableStateOf(false) }
    var localDragPos by remember { mutableStateOf(Offset.Zero) }
    // The entry being moved, shown optimistically at its new cell (by row id) until the flow catches up.
    var localOptimistic by remember { mutableStateOf<Pair<Long, Triple<Int, Int, Int>>?>(null) }

    // The pager ALWAYS carries one extra trailing page (so an icon can be dragged onto a brand new
    // page). The count is kept stable — never toggled by [dragging] — because changing it at pickup
    // forced a pager relayout exactly as the drag began, making pickup stutter and miss moves. The
    // trailing page is hidden from the dots and the workspace snaps back off it when not dragging,
    // so it reads as a single page until an icon actually lands there.
    // The user's page-switch speed (Settings > Home): one spring for the snap and every page change.
    // A State read at call time: the collectors and drag loops below outlive the composition that
    // started them, and a plain value would keep the speed they were born with.
    val pageSpec by rememberUpdatedState(pageSwitchSpec(LocalPageSwitchSpeed.current))
    val pagerState = rememberPagerState(initialPage = (homePage ?: 0).coerceIn(0, pageCount), pageCount = { pageCount + 1 })
    // Settings arrive a beat after the first frame: open on the home page once they are known.
    // Saved across recreation so a restored pager (rotation, locale change, process death) keeps
    // the page the user was on. Later home-page changes never move the pager — the page menu's
    // insert/remove rewrite the stored index while the user is on another page on purpose.
    var openedOnHome by rememberSaveable { mutableStateOf(false) }
    // The page the user was on, saved with the pager. After the process died behind another app
    // the activity is recreated with its saved state BEFORE the layout is known: the first frame
    // measures the pager with the placeholder count (one page), which clamps the restored page to
    // index 1 and writes that back. Once the rows arrive, the pager returns to the saved page.
    var lastPage by rememberSaveable { mutableIntStateOf(pagerState.currentPage) }
    LaunchedEffect(homePage != null) {
        val target = homePage ?: return@LaunchedEffect
        if (openedOnHome) {
            val restored = lastPage.coerceIn(0, pageCount - 1)
            if (pagerState.currentPage != restored) pagerState.scrollToPage(restored)
            return@LaunchedEffect
        }
        openedOnHome = true
        if (pagerState.currentPage != target) pagerState.scrollToPage(target.coerceIn(0, pageCount))
    }
    LaunchedEffect(pagerState, homePage != null) {
        if (homePage == null) return@LaunchedEffect // the placeholder's clamp is not the user's page
        snapshotFlow { pagerState.settledPage }.collect { lastPage = it }
    }
    // The pager's own count is the live bound: this collector never restarts, so the pageCount
    // parameter it closed over would be the first composition's.
    LaunchedEffect(pageRequests) {
        pageRequests.collect { page -> pagerState.animateScrollToPage(page.coerceIn(0, pagerState.pageCount - 1), animationSpec = pageSpec) }
    }
    // The cell the dragged icon would drop into — shown as a placeholder while dragging.
    var targetCell by remember { mutableStateOf<IntOffset?>(null) }

    val placedApps = remember(entries) { entries.filterIsInstance<PlacedApp>() }
    val folders = remember(entries) { entries.filterIsInstance<PlacedFolder>() }
    val placedShortcuts = remember(entries) { entries.filterIsInstance<PlacedShortcut>() }
    val placedWidgets = remember(entries) { entries.filterIsInstance<PlacedWidget>() }
    // Restored-but-unbound widget placeholders (tap to set up). No optimistic/drag handling — they are
    // transient until the user binds (→ PlacedWidget) or removes them.
    val pendingWidgets = remember(entries) { entries.filterIsInstance<PendingWidget>() }
    val builtins = remember(entries) { entries.filterIsInstance<PlacedBuiltin>() }
    val effectiveWidgets = remember(placedWidgets, widgetOptimistic) {
        val opt = widgetOptimistic
        if (opt == null) placedWidgets else placedWidgets.map { w ->
            if (w.rowId == opt.first) {
                w.copy(page = opt.second.page, cellX = opt.second.cellX, cellY = opt.second.cellY,
                    spanX = opt.second.spanX, spanY = opt.second.spanY)
            } else w
        }
    }
    val effectiveBuiltins = remember(builtins, widgetOptimistic) {
        val opt = widgetOptimistic
        if (opt == null) builtins else builtins.map { s ->
            if (s.rowId == opt.first) {
                s.copy(page = opt.second.page, cellX = opt.second.cellX, cellY = opt.second.cellY,
                    spanX = opt.second.spanX, spanY = opt.second.spanY)
            } else s
        }
    }
    // clear the optimistic override once the DB flow reports the widget at its new bounds. Keyed on
    // the override too: a drop (or resize) back onto the SAME bounds writes nothing, so the flow never
    // emits and the override used to stay forever — pinning the widget to that cell on screen even
    // after a later add pushed its row elsewhere.
    LaunchedEffect(placedWidgets, builtins, widgetOptimistic) {
        val opt = widgetOptimistic ?: return@LaunchedEffect
        val landed = (placedWidgets.map { it.rowId to WidgetBounds(it.page, it.cellX, it.cellY, it.spanX, it.spanY) } +
            builtins.map { it.rowId to WidgetBounds(it.page, it.cellX, it.cellY, it.spanX, it.spanY) })
            .any { it == opt }
        if (landed) widgetOptimistic = null
    }

    // Optimistic placements (key -> page/cellX/cellY) applied on top of [placedApps] until the
    // database flow catches up, so an icon doesn't flash at its old cell for a frame on drop.
    var optimistic by remember { mutableStateOf(emptyMap<String, Triple<Int, Int, Int>>()) }
    // Keys optimistically removed from home (dragged into the dock or a folder) — hidden until the
    // flow drops them, so the icon doesn't reappear at its old cell for a frame.
    var removedKeys by remember { mutableStateOf(emptySet<String>()) }
    // Keyed on the overrides too (see the widget override above): an icon dropped back onto its own
    // cell writes nothing, so without this its override would never be released.
    LaunchedEffect(placedApps, optimistic) {
        if (optimistic.isNotEmpty()) {
            // Released once the app shows at its target, or once it has left home altogether.
            optimistic = optimistic.filterNot { (key, pos) ->
                placedApps.none { it.app.key == key } || placedApps.any {
                    it.app.key == key && it.page == pos.first &&
                        it.cellX == pos.second && it.cellY == pos.third
                }
            }
        }
        if (removedKeys.isNotEmpty()) {
            removedKeys = removedKeys.filterTo(mutableSetOf()) { rk -> placedApps.any { it.app.key == rk } }
        }
    }
    // A hide whose write failed (a folder that couldn't be created, a dock that filled up meanwhile)
    // never sees its app leave home; without this the icon stayed invisible while still holding its
    // cell. Writes land in milliseconds, so anything still hidden after this long is shown again.
    LaunchedEffect(removedKeys) {
        if (removedKeys.isEmpty()) return@LaunchedEffect
        delay(REMOVED_HIDE_TIMEOUT_MS)
        removedKeys = emptySet()
    }
    val effectiveApps = remember(placedApps, optimistic, removedKeys) {
        placedApps
            .filterNot { it.app.key in removedKeys }
            .map { p ->
                optimistic[p.app.key]?.let { (pg, x, y) -> p.copy(page = pg, cellX = x, cellY = y) } ?: p
            }
    }
    // Clear the local optimistic override once the DB flow reports the entry (folder or shortcut) at
    // its new cell.
    LaunchedEffect(folders, placedShortcuts, localOptimistic) {
        val opt = localOptimistic ?: return@LaunchedEffect
        val (id, pos) = opt
        val landed = folders.any { it.id == id && it.page == pos.first && it.cellX == pos.second && it.cellY == pos.third } ||
            placedShortcuts.any { it.rowId == id && it.page == pos.first && it.cellX == pos.second && it.cellY == pos.third }
        if (landed) localOptimistic = null
    }
    val effectiveFolders = remember(folders, localOptimistic) {
        val opt = localOptimistic
        if (opt == null) folders else folders.map { f ->
            if (f.id == opt.first) f.copy(page = opt.second.first, cellX = opt.second.second, cellY = opt.second.third) else f
        }
    }
    val effectiveShortcuts = remember(placedShortcuts, localOptimistic) {
        val opt = localOptimistic
        if (opt == null) placedShortcuts else placedShortcuts.map { s ->
            if (s.rowId == opt.first) s.copy(page = opt.second.first, cellX = opt.second.second, cellY = opt.second.third) else s
        }
    }
    // Apps + folders + pinned shortcuts together — what's actually on the grid (rendering + occupants).
    val effectiveEntries: List<HomeEntry> = remember(effectiveApps, effectiveFolders, effectiveShortcuts, effectiveWidgets, pendingWidgets, effectiveBuiltins) {
        effectiveApps + effectiveFolders + effectiveShortcuts + effectiveWidgets + pendingWidgets + effectiveBuiltins
    }
    // The drag gesture's pointerInput block outlives recomposition (its keys don't include the entry
    // list), so it must read the *latest* placements through this state, not a stale closure capture.
    val latestEntries by rememberUpdatedState(effectiveEntries)
    // The empty-area detector below is a pointerInput(Unit) that lives as long as the page; read the
    // callbacks through rememberUpdatedState so a recomposition with a new lambda (a changed setting)
    // reaches it instead of the lambda captured at first composition.
    val currentEmptyAreaMenu by rememberUpdatedState(onEmptyAreaMenu)
    val currentEmptyAreaDoubleTap by rememberUpdatedState(onEmptyAreaDoubleTap)
    val currentWidgetDrag by rememberUpdatedState(widgetDragController)

    val cellW = if (columns > 0 && gridSize.width > 0) gridSize.width.toFloat() / columns else 1f
    val cellH = if (rows > 0 && gridSize.height > 0) gridSize.height.toFloat() / rows else 1f
    // Icon/folder/shortcut size derived from the cell so 6–7 columns fit a narrow screen (a fixed
    // 52dp icon bled into neighbouring cells there) AND so a whole label line fits under it in a
    // short cell (7–8 rows on an enlarged display size). 52dp until the grid reports its real size.
    val labelBlock = labelBlockHeight(
        showLabel = showLabels,
        labelScale = LocalAppLabelScale.current,
        fontFactor = labelFontFactor(density.fontScale),
        lines = LocalAppLabelLines.current,
    )
    // The user's icon-size setting scales the nominal 52dp BEFORE the cell clamp, so a dense grid still
    // caps the icon and the label keeps its room.
    val nominalIconSize = 52.dp * LocalIconScale.current
    val cellIconSize = if (gridSize.width > 0 && gridSize.height > 0) {
        iconSizeForCell(with(density) { cellW.toDp() }, nominalIconSize, with(density) { cellH.toDp() }, labelBlock)
    } else {
        nominalIconSize
    }
    // A widget's margin inside its cells when it is to line up with the icons (a Telegram request).
    val widgetInsetDp = widgetInset(with(density) { cellW.toDp() }, cellIconSize, widgetEdgesAlignWithIcons)
    val moveThresholdPx = with(density) { 16.dp.toPx() }
    // Page flips only when the dragged icon is pushed right against the screen edge.
    val edgePx = with(density) { 20.dp.toPx() }

    // Client-side mirror of rectFitsForRow over the rendered entries, for the live drag placeholder.
    fun rectFreeOnGrid(excludeRowId: Long, page: Int, x: Int, y: Int, spanX: Int, spanY: Int): Boolean {
        if (x < 0 || y < 0 || x + spanX > columns || y + spanY > rows) return false
        val occupied = HashSet<Triple<Int, Int, Int>>()
        for (e in effectiveEntries) {
            val (ex, ey) = when (e) {
                is PlacedWidget -> e.spanX to e.spanY
                is PendingWidget -> e.spanX to e.spanY
                is PlacedBuiltin -> e.spanX to e.spanY
                else -> 1 to 1
            }
            val erow = when (e) {
                is PlacedWidget -> e.rowId
                is PendingWidget -> e.rowId
                is PlacedBuiltin -> e.rowId
                else -> -2L
            }
            if (erow == excludeRowId) continue
            for (dx in 0 until ex) for (dy in 0 until ey) occupied += Triple(e.page, e.cellX + dx, e.cellY + dy)
        }
        for (dx in 0 until spanX) for (dy in 0 until spanY) if (Triple(page, x + dx, y + dy) in occupied) return false
        return true
    }

    fun canPlaceWidget(p: WidgetPlacement, rowId: Long = Long.MIN_VALUE): Boolean =
        ReorderPlanner.planFit(latestEntries.widgetRects(), rowId, p.page, p.cellX, p.cellY,
            p.spanX, p.spanY, columns, rows) != null

    fun liftWidget(item: EditingItem, root: Offset, fraction: Offset, owner: Any) {
        // A second finger on another widget: the first drag keeps the controller (no snapshot, no buzz).
        if (widgetDragController.active != null) return
        val bitmap = item.appWidgetId?.let { id ->
            widgetViews[id]?.let { view -> runCatching { view.drawToBitmap() }.getOrNull() }
        }
        val builtin = builtins.firstOrNull { it.rowId == item.rowId }
        val choice = builtin?.let { WidgetChoice.Builtin(it.type) } ?: item.appWidgetId?.let { id ->
            runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(id) }.getOrNull()
                ?.let { WidgetChoice.App(it) }
        }
        widgetDragController.start(
            WidgetDrag(item, choice,
                item.spanX, item.spanY, fraction, bitmap), root, owner,
        )
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val configuration = LocalConfiguration.current
    SideEffect {
        widgetDragController.cellWidthDp = cellW / density.density
        widgetDragController.cellHeightDp = cellH / density.density
        // The same size for the rest of the process (a widget request's confirmation), once real.
        if (gridSize.width > 0 && gridSize.height > 0) cellSize.publish(cellW / density.density, cellH / density.density, columns, rows, configuration, chrome)
        widgetDragController.target = {
            val drag = widgetDragController.active
            if (drag == null || locked || pagerState.isScrollInProgress) null else {
                val pos = widgetDragController.position - dragController.gridBounds.topLeft
                widgetDropPlacement(pagerState.currentPage, pos.x, pos.y,
                    gridSize.width.toFloat(), gridSize.height.toFloat(), columns, rows,
                    drag.spanX, drag.spanY, drag.grabFraction.x, drag.grabFraction.y
                )?.takeIf { canPlaceWidget(it, drag.item?.rowId ?: Long.MIN_VALUE) }
            }
        }
        widgetDragController.findSpace = { sx, sy ->
            // The current page first, then the rest including the trailing one — but never a page
            // the pager only offers while dragging (at the cap it is the first page's copy).
            val pages = (listOf(pagerState.currentPage) + (0..pageCount).filter { it != pagerState.currentPage })
                .filter { HomeLayoutRepository.isShownPage(it) }
            pages.firstNotNullOfOrNull { page ->
                val candidates = (0..(rows - sy)).flatMap { y ->
                    (0..(columns - sx)).map { x -> WidgetPlacement(page, x, y, sx, sy) }
                }
                candidates.firstOrNull { rectFreeOnGrid(Long.MIN_VALUE, it.page, it.cellX, it.cellY, sx, sy) }
                    ?: candidates.firstOrNull { canPlaceWidget(it) }
            }
        }
        widgetDragController.keepsGestureLock = { editingWidget != null }
        widgetDragController.onStill = { drag ->
            drag.item?.let { editingWidget = it }
        }
        widgetDragController.onDrop = { drag, target ->
            val item = drag.item
            if (!locked && item != null && dragController.isOverRemove(widgetDragController.position)) {
                onRemoveWidget(item.rowId, item.appWidgetId)
                editingWidget = null
            } else if (!locked && target != null) {
                if (item != null) {
                    editingWidget = null
                    widgetOptimistic = item.rowId to WidgetBounds(target.page, target.cellX, target.cellY, target.spanX, target.spanY)
                    scope.launch {
                        if (onSetWidgetBounds(item.rowId, target.page, target.cellX, target.cellY, target.spanX, target.spanY)) {
                            editingWidget = item.copy(page = target.page, cellX = target.cellX, cellY = target.cellY,
                                spanX = target.spanX, spanY = target.spanY)
                        } else {
                            widgetOptimistic = null
                        }
                    }
                } else {
                    drag.choice?.let { onAddWidget(it, target) }
                }
            }
        }
    }
    DisposableEffect(widgetDragController) {
        onDispose {
            widgetDragController.cancel()
            widgetDragController.target = null
            widgetDragController.findSpace = null
            widgetDragController.onDrop = null
            widgetDragController.onStill = null
            widgetDragController.keepsGestureLock = { false }
        }
    }
    LaunchedEffect(widgetDragController.active, widgetDragController.moving, pageCount) {
        if (!widgetDragController.moving) return@LaunchedEffect
        snapshotFlow {
            val p = widgetDragController.position - dragController.gridBounds.topLeft
            when {
                p.y < 0 || p.y >= gridSize.height -> 0
                p.x < edgePx -> -1
                p.x > gridSize.width - edgePx -> 1
                else -> 0
            }
        }.collectLatest { direction ->
            if (direction == 0) return@collectLatest
            while (widgetDragController.moving) {
                delay(EDGE_FLIP_DWELL_MS)
                val next = (pagerState.currentPage + direction).coerceIn(0, pageCount)
                if (next == pagerState.currentPage) break
                // In [scope]: a drop mid-flip restarts this effect, which must not strand the pager.
                scope.launch(start = CoroutineStart.UNDISPATCHED) { pagerState.animateScrollToPage(next, animationSpec = pageSpec) }
                snapshotFlow { pagerState.isScrollInProgress }.first { !it }
            }
        }
    }

    // Shared drag for a local home entry (folder or pinned shortcut): long-press lifts; drag moves it
    // by [rowId] to a cell (swapping any occupant); a tap is [onTap]; a still long-press is
    // [onStillPress] (open folder / show shortcut menu). Mirrors the app drag but stays on the grid.
    @Composable
    fun Modifier.localEntryDrag(
        entry: HomeEntry,
        rowId: Long,
        removable: Boolean,
        onTap: () -> Unit,
        onStillPress: () -> Unit,
        onRemove: () -> Unit,
        // The desktop lock, or the copy of page 0 at the tail (taps only).
        pageLocked: Boolean = locked,
    ): Modifier {
        // The detector outlives the composition that made it: the entry is left out of its keys so
        // a change to it (a shortcut the app disables or enables again, a folder's visible apps)
        // can't restart the detector mid-hold. So what a gesture hands on is read when it fires —
        // through these — not from the closure the detector was created with: a tap on a shortcut
        // the app had enabled again used to launch it as still disabled.
        val currentEntry by rememberUpdatedState(entry)
        val currentOnTap by rememberUpdatedState(onTap)
        val currentOnStillPress by rememberUpdatedState(onStillPress)
        val currentOnRemove by rememberUpdatedState(onRemove)
        // [removable] is a key: a folder's removability changes with its visible apps, and a detector
        // holding the old value could remove a folder that shows apps again.
        return this.pointerInput(rowId, entry.page, entry.cellX, entry.cellY, cellW, cellH, columns, rows, pageLocked, removable) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                // The root swipe detector (Initial pass) learns which folder THIS finger is on, so a swipe up
                // from it can open the folder's first app when that setting is on (issue #17). Dropped when
                // this gesture ends, however it ends.
                val pressedFolder = (currentEntry as? PlacedFolder)?.id
                if (pressedFolder != null) dragController.folderPressed(down.id.value, pressedFolder)
                try {
                // Shared press phase (tap / yield to a claimed swipe / hold). The lift starts where the
                // finger is when the hold completes — the hold tolerates a roll across the icon.
                val press = awaitIconPress(down)
                if (press is IconPressOutcome.Tap) { currentOnTap(); return@awaitEachGesture }
                if (press !is IconPressOutcome.Held) return@awaitEachGesture
                val holdPos = press.position
                // Desktop locked: long-press does nothing (no move/remove); tap (above) still opens.
                if (pageLocked) return@awaitEachGesture
                // PICK UP — refused while another finger holds a lift (see HomeDragController.claim).
                // A folder lifts in the controller too (as a folder, not an app): the dock reads the
                // lift to light up for it, and the shell still draws nothing for it.
                val token = Any()
                val folderId = (currentEntry as? PlacedFolder)?.id
                val claimed = if (folderId != null) {
                    val root = dragController.gridBounds.topLeft + Offset(entry.cellX * cellW + holdPos.x, entry.cellY * cellH + holdPos.y)
                    dragController.startFolder(folderId, DragSource.Home, root, token)
                } else {
                    dragController.claim(token)
                }
                if (!claimed) return@awaitEachGesture
                draggingLocal = currentEntry
                localMoving = false
                // Claim the gesture for this local entry so the root swipe-up detector (which runs in the
                // Initial pass, before us) won't steal the first movement after this long-press.
                dragController.localGestureActive = true
                localDragPos = Offset(entry.cellX * cellW + holdPos.x, entry.cellY * cellH + holdPos.y)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val dragStartPx = IconPress.DragStartThreshold.toPx()
                try {
                    val completed = dragPointer(down.id) { change ->
                        val delta = change.positionChange()
                        change.consume()
                        if (!dragController.owns(token)) return@dragPointer
                        localDragPos += delta
                        // Same drag-start threshold as an app icon: a wobbling hold opens the folder / menu.
                        if (!localMoving && IconPress.startsDrag(change.position, holdPos, dragStartPx)) {
                            localMoving = true
                            if (removable) dragController.localDragging = true
                            // A folder's move is "moving" in the controller without leaving its
                            // source: nothing lights up on the grid for it, but the dock can.
                            if (folderId != null) dragController.beginMove(leftSource = false)
                        }
                        if (!localMoving) return@dragPointer
                        // Publish root coords so the remove zone (a removable entry) and the dock (a
                        // folder) can highlight + hit-test this local drag.
                        if (removable || folderId != null) dragController.update(dragController.gridBounds.topLeft + localDragPos)
                        val cx = (localDragPos.x / cellW).toInt().coerceIn(0, columns - 1)
                        val cy = (localDragPos.y / cellH).toInt().coerceIn(0, rows - 1)
                        val nc = IntOffset(cx, cy)
                        if (nc != targetCell) targetCell = nc
                    }
                    targetCell = null
                    val rootPos = dragController.gridBounds.topLeft + localDragPos
                    // Nothing on release once HOME cancelled the lift.
                    val live = completed && dragController.owns(token)
                    if (live && localMoving && removable && dragController.isOverRemove(rootPos)) {
                        currentOnRemove()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else if (live && localMoving && folderId != null && dragController.isOverDock(rootPos)) {
                        // A folder into the dock, between its tiles, while the dock has room.
                        if (dragController.dockHasSpace) {
                            onMoveFolderToDock(folderId, dragController.dockIndexAt(rootPos))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else if (live && localMoving) {
                        val tx = (localDragPos.x / cellW).toInt().coerceIn(0, columns - 1)
                        val ty = (localDragPos.y / cellH).toInt().coerceIn(0, rows - 1)
                        // targetPage, not currentPage: a release while an edge flip is still animating
                        // lands on the page arriving under the finger, not the one leaving.
                        val targetPage = pagerState.targetPage
                        if (targetPage != entry.page || tx != entry.cellX || ty != entry.cellY) {
                            localOptimistic = rowId to Triple(targetPage, tx, ty)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                if (!onMoveFolder(rowId, targetPage, tx, ty)) localOptimistic = null
                            }
                        }
                    } else if (live) {
                        currentOnStillPress()
                    }
                } finally {
                    // Reset even on cancellation (node disposed / pointerInput restarted), so a stuck flag
                    // can never leave the root swipe detector permanently disabled. A lift HOME already
                    // cancelled was reset there, and the state may belong to a newer gesture by now.
                    if (dragController.owns(token)) {
                        dragController.localDragging = false
                        dragController.localGestureActive = false
                        draggingLocal = null
                        localMoving = false
                        dragController.stop(token)
                    }
                }
                } finally {
                    if (pressedFolder != null) dragController.folderReleased(down.id.value, pressedFolder)
                }
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = editingWidget != null || widgetDragController.active != null) {
        widgetDragController.cancel()
        editingWidget = null
    }

    // HOME button / home gesture: always leave widget edit mode, but snap to the first page only
    // when HOME was pressed while the launcher was already foreground (alreadyOnHome). Coming home
    // from an app keeps the page the app was launched from — the Pixel Launcher convention.
    val homeIndex = homePage ?: 0
    // Counts HOME presses, so a reveal that waited for its rows (below) can tell the user moved on.
    var homePresses by remember { mutableIntStateOf(0) }
    LaunchedEffect(homeSignals, pageCount, homeIndex) {
        homeSignals.collect { alreadyOnHome ->
            homePresses++
            widgetDragController.cancel()
            // A held icon, folder or shortcut lift ends too; its gesture drops nothing on release.
            dragController.cancel()
            dragging = null
            draggingLocal = null
            localMoving = false
            targetCell = null
            editingWidget = null
            // In [scope], not here: this effect restarts when the page count changes, and a turn
            // cancelled half way left the pager stranded between two pages until the next touch.
            if (alreadyOnHome && pagerState.currentPage != homeIndex) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) { pagerState.animateScrollToPage(homeIndex, animationSpec = pageSpec) }
            }
        }
    }

    // An edit frame whose widget is gone (provider uninstalled, a restore swapped it for a placeholder)
    // left a scrim over an empty cell with the pager and the swipe-up locked until tapped.
    LaunchedEffect(editingWidget, effectiveEntries) {
        val ew = editingWidget ?: return@LaunchedEffect
        val present = effectiveEntries.any {
            (it is PlacedWidget && it.rowId == ew.rowId) || (it is PlacedBuiltin && it.rowId == ew.rowId)
        }
        if (!present) editingWidget = null
    }
    // Leaving the launcher (screen off, another app) also leaves edit mode, like HOME does.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        widgetDragController.cancel()
        editingWidget = null
    }

    // Only retreat from the temporary trailing page. Pages explicitly added from the menu are
    // permanent even when empty, including pages beyond the last icon or widget.
    val settledPage = pagerState.settledPage
    // A drawer/dock→home drag flips to an empty page ON PURPOSE so the app can be dropped there; the
    // retreat must yield to it (otherwise the page snaps back to the front under the finger — the
    // "it won't stay on the other page" bug). The in-home icon/local drags are already covered by
    // dragging/draggingLocal.
    // A drag out of the open folder joins in once it has left the card (leftSource): a reorder inside
    // the card — which sits in the edge-flip zone — must not page behind it.
    val crossSurfaceDrag = dragController.isIncomingFrom(DragSource.Drawer, DragSource.Dock, DragSource.Folder)

    // Infinite scrolling (WorkspaceWrap.kt): outside a drag the trailing page shows a copy of page 0.
    // Any drag (or the widget edit frame) turns it back into the empty new page, and it stays that
    // until the pager rests on a real page — so a drop onto the new page, or the retreat after one,
    // never shows page 0 there. The drag code itself never sees the copy.
    // A drag counts from its lift, still or moving, on every surface (isDragging), as a home icon's
    // does (dragging). A function, so callbacks can ask at call time: a dock lift leaves the pager
    // scrollable, so a second finger can still pull it while one is held.
    fun dragInProgress(): Boolean = dragging != null || draggingLocal != null || dragController.isDragging ||
        widgetDragController.active != null || editingWidget != null
    val anyDrag = dragInProgress()
    var newPageLatched by remember { mutableStateOf(false) }
    LaunchedEffect(anyDrag, pageCount) {
        if (anyDrag) {
            newPageLatched = true
            return@LaunchedEffect
        }
        // Released only once the pager RESTS on a real page: a drag can end while its edge flip is
        // still animating onto the new page, and settledPage still names the page it left then.
        snapshotFlow { !pagerState.isScrollInProgress && pagerState.settledPage < pageCount }.first { it }
        newPageLatched = false
    }
    val tailCopy = tailShowsPageZero(infiniteScroll, pageCount, anyDrag, newPageLatched)
    // Which instance of page 0 hosts its widget views (ownsWidgetViews). Follows the pager, and is set
    // ahead of each jump so the instance arriving on screen attaches them in its very first frame.
    var copyOnScreen by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState, pageCount, tailCopy) {
        snapshotFlow {
            tailCopy && tailCopyOnScreen(pagerState.currentPage + pagerState.currentPageOffsetFraction, pageCount)
        }.collect { copyOnScreen = it }
    }
    PageZeroCopySwap(pagerState, pageCount, tailCopy, onBeforeSwap = { copyOnScreen = false })

    // "Show the new item": rows other apps' pin requests added while home was away ([newItems]).
    // Taken when home comes to the foreground at rest — otherwise they wait for the next time, so
    // the page never turns under the user's own drag or behind the open drawer. The rows reach
    // [entries] a beat later (the layout flow restarts after a long stay in the background), so the
    // reveal waits for them, but only briefly: a row removed meanwhile never comes.
    var revealing by remember { mutableStateOf<List<Long>?>(null) }
    // Pop-in progress per revealed row (0 = still hidden, 1 = settled), read in the item's layer.
    // Set as soon as the ids are taken, so a row can't show at full size before its pop.
    val revealScale = remember { mutableStateMapOf<Long, Float>() }
    val currentDrawerOpen by rememberUpdatedState(drawerOpen)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (revealing == null && !dragInProgress() && !drawerOpen) {
            val ids = newItems.take()
            if (ids.isNotEmpty()) {
                ids.forEach { revealScale[it] = 0f }
                revealing = ids
            }
        }
    }
    LaunchedEffect(revealing) {
        val ids = revealing ?: return@LaunchedEffect
        val homePressesAtStart = homePresses
        val pageAtStart = pagerState.currentPage
        try {
            // The wait can take seconds. Whatever the user does meanwhile — HOME, the drawer, a drag, a
            // page of their own — wins, even when it is over again by the end of the wait: the rows
            // still pop in, but the page stays where the user is.
            var userActed = false
            coroutineScope {
                val watch = launch {
                    snapshotFlow { currentDrawerOpen || dragInProgress() || pagerState.isScrollInProgress }.first { it }
                    userActed = true
                }
                withTimeoutOrNull(NEW_ITEM_WAIT_MS) {
                    snapshotFlow { ids.all { id -> latestEntries.any { it.rowIdOrNull == id } } }.first { it }
                }
                watch.cancel()
            }
            val present = ids.filter { id -> latestEntries.any { it.rowIdOrNull == id } }
            if (present.isEmpty()) return@LaunchedEffect
            val userMovedOn = userActed || homePresses != homePressesAtStart || currentDrawerOpen || dragInProgress() ||
                pagerState.isScrollInProgress || pagerState.currentPage != pageAtStart
            // The last one's page. With the copy of page 0 under the pager, page 0 is already showing.
            val target = latestEntries.first { it.rowIdOrNull == present.last() }.page
            val showing = if (tailCopy && pagerState.currentPage >= pageCount) 0 else pagerState.currentPage
            if (!userMovedOn && target != showing && target in 0 until pagerState.pageCount) {
                try {
                    pagerState.animateScrollToPage(target, animationSpec = pageSpec)
                } catch (e: CancellationException) {
                    // The pager cancels the turn when the user starts dragging or another scroll
                    // (HOME on the home screen) takes over. This coroutine is still alive then, and
                    // the pop below must still run — the rows were hidden for it.
                    coroutineContext.ensureActive()
                }
            }
            delay(NEW_ITEM_POP_PAUSE_MS)
            // Launcher3's bind animation (Apache-2.0): each new icon pops in from nothing with an
            // overshoot, one after another. Waited for, so the restart below can't cut them short.
            coroutineScope {
                present.forEachIndexed { i, id ->
                    launch {
                        animate(0f, 1f, animationSpec = tween(NEW_ITEM_POP_MS, i * NEW_ITEM_STAGGER_MS, NewItemOvershoot)) { v, _ ->
                            revealScale[id] = v
                        }
                    }
                }
            }
        } finally {
            // However the reveal ends — done, timed out, or cut short by the effect leaving — no row
            // may stay hidden: a scale of 0 keeps the item invisible while it holds its cell and takes taps.
            ids.forEach { revealScale.remove(it) }
            revealing = null
        }
    }
    // A swipe or an accessibility page action that ran into an end (on past the last page, or back on
    // page 0 when it neither wraps nor belongs to the left-edge app), or the retreat's bounce off the
    // empty page past the last one. Each shows PageEndHint; the swipes and page actions say so the same
    // way at both ends, but not during a drag nor just after one.
    var pageEndBounces by remember { mutableIntStateOf(0) }
    fun sayNoMorePages() {
        if (!dragInProgress() && !newPageLatched) pageEndBounces++
    }

    LaunchedEffect(settledPage, pageCount, dragging, draggingLocal, crossSurfaceDrag, widgetDragController.active, tailCopy) {
        // With the copy of page 0 at the tail there is nothing to retreat from: PageZeroCopySwap
        // moves on to the real page 0 instead.
        if (tailCopy || dragging != null || draggingLocal != null || crossSurfaceDrag || widgetDragController.active != null || settledPage < pageCount) return@LaunchedEffect
        // After a cross-surface drag ends, the dropped app reaches this page via the DB flow a beat
        // later — wait briefly before deciding the page is empty, so a valid drop isn't undone.
        // A page-count change cancels this wait: the trailing page may have become permanent.
        if (latestEntries.none { it.page == settledPage }) kotlinx.coroutines.delay(180)
        if (dragging == null && draggingLocal == null && widgetDragController.active == null) {
            val target = emptyPageReturnTarget(
                settledPage = settledPage,
                permanentPageCount = pageCount,
                hasContent = latestEntries.any { it.page == settledPage },
            )
            if (target != null) {
                // Not after a drag that just ended here: that drop page was the user's own choice.
                if (!newPageLatched) pageEndBounces++
                pagerState.animateScrollToPage(target, animationSpec = pageSpec)
            }
        }
    }

    // Publish the grid metrics so a dock→home drop can map the finger's root position to a cell.
    LaunchedEffect(columns, rows) {
        dragController.columns = columns
        dragController.rows = rows
    }
    LaunchedEffect(pagerState, pageCount) {
        // Allow the always-present trailing page (index == pageCount) so a drawer/dock drop can land on
        // a BRAND-NEW page. Clamping to pageCount-1 made cellAt() report the last existing page, so an
        // app dragged from the drawer onto a new page always saved to the front page instead.
        snapshotFlow { pagerState.targetPage }
            .collect { dragController.currentPage = it.coerceIn(0, pageCount) }
    }
    // Edge page-flip for every icon drag — a home icon, a folder or shortcut, and a drawer/dock→home
    // drag — after the same dwell as a widget drag (Launcher3 also waits before scrolling). Flipping on
    // the first frame at the edge made a finger resting there page on and on. The animation runs in
    // [scope], not here, so ending the drag mid-flip can't leave the pager stranded between pages.
    LaunchedEffect(pagerState, pageCount) {
        snapshotFlow {
            val p = when {
                dragging != null && dragController.moving -> dragPos
                draggingLocal != null && localMoving -> localDragPos
                dragController.isIncomingFrom(DragSource.Drawer, DragSource.Dock, DragSource.Folder) ->
                    dragController.rootPosition - dragController.gridBounds.topLeft
                else -> null
            }
            when {
                p == null || p.y < 0 || p.y >= gridSize.height -> 0
                p.x < edgePx -> -1
                p.x > gridSize.width - edgePx -> 1
                else -> 0
            }
        }.collectLatest { direction ->
            if (direction == 0) return@collectLatest
            while (true) {
                delay(EDGE_FLIP_DWELL_MS)
                val next = (pagerState.targetPage + direction).coerceIn(0, pageCount)
                if (next == pagerState.targetPage) break
                scope.launch(start = CoroutineStart.UNDISPATCHED) { pagerState.animateScrollToPage(next, animationSpec = pageSpec) }
                snapshotFlow { pagerState.isScrollInProgress }.first { !it }
            }
        }
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            val pagerScrolls = dragging == null && draggingLocal == null && editingWidget == null && widgetDragController.active == null
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = pagerScrolls,
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapAnimationSpec = pageSpec),
                // While an item is MOVING, keep every page composed so flipping to another page can't
                // dispose the dragged item's node (which would cancel the in-progress drag); this also
                // covers a drawer/dock→home drag, so its target page renders as pages flip. A still
                // long-press only lifts — composing every page (widgets, calendar queries) at that
                // moment made the menu open with a stutter, and no page can flip before a move.
                beyondViewportPageCount = if (
                    dragController.moving || localMoving || widgetDragController.moving
                ) pageCount.coerceAtLeast(0) else 0,
                overscrollEffect = rememberWrapAroundOverscroll(
                    pagerState = pagerState,
                    pageCount = pageCount,
                    tailShowsPageZero = tailCopy,
                    leftEdgeAction = leftEdgeActionEnabled,
                    onWrap = { toTailCopy -> copyOnScreen = toTailCopy },
                    onStartOverscroll = ::sayNoMorePages,
                    onEndOverscroll = ::sayNoMorePages,
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Paging with TalkBack or Switch Access does at an end what a swipe does there.
                    .wrapAroundPagingSemantics(
                        pageSpec = pageSpec,
                        pagerState = pagerState,
                        pageCount = pageCount,
                        enabled = pagerScrolls,
                        tailShowsPageZero = tailCopy,
                        leftEdgeAction = leftEdgeActionEnabled,
                        scope = scope,
                        onWrap = { copyOnScreen = true },
                        onEndReached = ::sayNoMorePages,
                    )
                    .onSizeChanged { gridSize = it }
                    .onGloballyPositioned { dragController.gridBounds = it.boundsInRoot() },
            ) { pagerPage ->
                // With infinite scrolling the tail is a copy of page 0 (WorkspaceWrap.kt). It draws page
                // 0's content but takes taps only (pageLocked) and never opens the empty-area menu: a
                // lift or a page operation there would land on the tail's index, not on page 0. It is
                // on screen only while the pager slides through it.
                val isTailCopy = tailCopy && pagerPage == pageCount
                val page = if (isTailCopy) 0 else pagerPage
                val pageLocked = locked || isTailCopy
                val onTailCopy by rememberUpdatedState(isTailCopy)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Drop placeholder, drawn (not composed) so moving it across cells during a
                        // drag never triggers recomposition — keeps the drag smooth.
                        .drawBehind {
                            val onThisPage = page == pagerState.currentPage && cellW > 1f
                            // Placeholder cell: our own in-home drag uses [targetCell]; a dock→home
                            // drag (driven by the shared controller) computes it from the finger.
                            val tc: IntOffset? = when {
                                // Local relocation (folder or pinned shortcut): a folder may leave for the
                                // dock, and the cell hint goes while it hovers there, as for an app.
                                draggingLocal != null && localMoving -> {
                                    if (draggingLocal is PlacedFolder && dragController.isOverDock(dragController.rootPosition)) null
                                    else targetCell
                                }
                                // Our own home drag: hide the cell hint while hovering the dock.
                                dragging != null && dragController.moving -> {
                                    if (dragController.isOverDock(dragController.rootPosition)) null else targetCell
                                }
                                dragController.isIncomingFrom(DragSource.Dock, DragSource.Drawer, DragSource.Folder) &&
                                    dragController.isOverGrid(dragController.rootPosition) -> {
                                    val (_, cx, cy) = dragController.cellAt(dragController.rootPosition)
                                    // A dock folder lands only on a free cell (folders never merge).
                                    if (dragController.draggedFolderId != null && cellTaken(effectiveEntries, page, cx, cy)) null
                                    else IntOffset(cx, cy)
                                }
                                else -> null
                            }
                            if (tc != null && onThisPage) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.32f),
                                    radius = 29.dp.toPx(),
                                    center = Offset(
                                        (tc.x + 0.5f) * cellW,
                                        (tc.y + 0.5f) * cellH,
                                    ),
                                )
                            }
                        }
                        // NOTE: the drawer/notifications swipe-up detector used to live here, on the
                        // page background, so it only caught swipes from empty space. It was hoisted to
                        // HomeScreen's root Box (see Modifier.pixelHomeSwipe) and runs in the Initial
                        // pointer pass, so a flick now wins the gesture even over icons, folders,
                        // shortcuts and the dock — matching Pixel Launcher. Only the still-long-press
                        // settings detector remains a page-background gesture.
                        .pointerInput(Unit) {
                            // Still long-press on empty space opens settings. Times out a touch
                            // later than the icon long-press, so an icon pickup (which consumes the
                            // pointer) always wins and suppresses this. The same detector also spots
                            // clean empty-area taps for double-tap-to-lock: nothing is consumed and
                            // nothing fires on a single tap, so every existing gesture is untouched.
                            var lastTapUpMs = 0L
                            var lastTapPos = Offset.Zero
                            val doubleTapSlopPx = 48.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = true)
                                var resolved = false
                                var tapUpMs = -1L
                                val held = withTimeoutOrNull(
                                    viewConfiguration.longPressTimeoutMillis + 180L,
                                ) {
                                    while (!resolved) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed || change.isConsumed ||
                                            (change.position - down.position).getDistance() >
                                            viewConfiguration.touchSlop
                                        ) {
                                            // A clean finger-up (unconsumed, within slop) completed a tap.
                                            if (change != null && !change.pressed && !change.isConsumed &&
                                                (change.position - down.position).getDistance() <=
                                                viewConfiguration.touchSlop
                                            ) {
                                                tapUpMs = change.uptimeMillis
                                            }
                                            resolved = true
                                        }
                                    }
                                }
                                // Fired only on a still empty-area hold. If an icon picked up
                                // (dragging != null) the hold belonged to that icon, not settings.
                                // A lifted widget owns the hold too: its gesture only OBSERVES the press (the
                                // hosted view must keep its taps), so this detector still sees the still
                                // dwell — without the check the pickup haptic was followed by this menu.
                                // Also suppressed while a widget is in edit mode (editingWidget != null):
                                // a still dwell on the widget during a move/remove drag must not also
                                // open the home-options menu underneath the edit scrim.
                                if (held == null && !resolved && dragging == null && editingWidget == null && currentWidgetDrag.active == null && !onTailCopy) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // Anchor the options popup at the press point (root coords); flip it
                                    // above when the press is in the lower half of the screen.
                                    val anchorPt = dragController.gridBounds.topLeft + down.position
                                    currentEmptyAreaMenu(
                                        IntOffset(anchorPt.x.roundToInt(), anchorPt.y.roundToInt()),
                                        anchorPt.y > windowHeightPx * 0.45f,
                                    )
                                }
                                if (tapUpMs >= 0 && dragging == null && editingWidget == null && currentWidgetDrag.active == null) {
                                    // Double-tap = previous tap's UP → this tap's DOWN within the
                                    // platform timeout, close enough together on screen.
                                    if (down.uptimeMillis - lastTapUpMs <= viewConfiguration.doubleTapTimeoutMillis &&
                                        (down.position - lastTapPos).getDistance() <= doubleTapSlopPx
                                    ) {
                                        lastTapUpMs = 0L
                                        currentEmptyAreaDoubleTap()
                                    } else {
                                        lastTapUpMs = tapUpMs
                                        lastTapPos = down.position
                                    }
                                }
                            }
                        },
                ) {
                    effectiveEntries.asSequence()
                        .filter { it.page == page }
                        .forEach { entry ->
                            // Keyed by item: without it each slot was bound by position, so a drop
                            // elsewhere on the page (or a page-count change) re-bound slots and
                            // restarted their gesture detectors — cancelling a long-press in progress.
                            key(homeEntryKey(entry)) {
                            when (entry) {
                            is PlacedFolder -> {
                                val folderBadge = entry.apps.sumOf { badges[it.badgeKey] ?: 0 }
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            val sx = entry.cellX.coerceIn(0, columns - 1)
                                            val sy = entry.cellY.coerceIn(0, rows - 1)
                                            IntOffset((sx * cellW).roundToInt(), (sy * cellH).roundToInt())
                                        }
                                        .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
                                        // Hide (but keep composed) the in-grid folder once it's moving;
                                        // the floating copy is drawn on top. While only lifted it stays.
                                        .graphicsLayer {
                                            alpha = if ((draggingLocal as? PlacedFolder)?.id == entry.id && localMoving) 0f else 1f
                                        }
                                        .localEntryDrag(
                                            entry = entry,
                                            rowId = entry.id,
                                            // Only a folder that shows no apps (all disabled or gone)
                                            // can be dragged to Remove; otherwise it could never go.
                                            removable = entry.apps.isEmpty(),
                                            onTap = { onOpenFolder(entry) },
                                            onStillPress = { onOpenFolder(entry) },
                                            onRemove = { onRemoveFolder(entry.id) },
                                            pageLocked = pageLocked,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    FolderIcon(
                                        name = entry.name,
                                        apps = entry.apps,
                                        showLabel = showLabels,
                                        showName = showFolderNames,
                                        badgeCount = folderBadge,
                                        badgeShowCount = badgeShowCount,
                                        badgeScale = badgeScale,
                                        labelColor = labelColor,
                                        size = cellIconSize,
                                        customIcon = entry.icon,
                                    )
                                }
                            }
                            is PlacedApp -> {
                            val placed = entry
                            // The gesture below lives as long as the cell's keys and must act on the
                            // item as it is NOW: with the first touch's item, Edit opened a second time
                            // showed the old icon and saving a name wiped a just-picked picture.
                            val currentPlaced by rememberUpdatedState(placed)
                            // The whole cell takes the tap; the launch zooms from the icon in it.
                            val launchOrigin = rememberAppLaunchOrigin()
                            Box(
                                modifier = Modifier
                                    .offset {
                                        // Safety net only: the repository reflows on column shrink,
                                        // but clamp here too so a stale out-of-range cell can never
                                        // draw off-screen for a frame before the reflow lands.
                                        val safeCellX = placed.cellX.coerceIn(0, columns - 1)
                                        val safeCellY = placed.cellY.coerceIn(0, rows - 1)
                                        IntOffset(
                                            (safeCellX * cellW).roundToInt(),
                                            (safeCellY * cellH).roundToInt(),
                                        )
                                    }
                                    .size(
                                        with(density) { cellW.toDp() },
                                        with(density) { cellH.toDp() },
                                    )
                                    // Hide (but keep in composition) the icon once it's actually
                                    // moving — the floating copy is drawn on top. While only lifted
                                    // (menu showing) it stays visible.
                                    .graphicsLayer {
                                        alpha = if (dragging?.app?.key == placed.app.key &&
                                            dragController.moving
                                        ) 0f else 1f
                                    }
                                    .appLaunchTouch(placed.app, launchOrigin)
                                    .pointerInput(
                                        placed.app.key, placed.page, placed.cellX, placed.cellY,
                                        cellW, cellH, columns, rows, pageLocked,
                                    ) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            // Claim the DOWN (the empty-area long-press detector
                                            // needs an unconsumed one) but not the moves: a swipe
                                            // that starts on an icon belongs to whoever claims it
                                            // past the slop — the root drawer/notification swipe or
                                            // the pager — exactly as on empty space (awaitIconPress).
                                            down.consume()
                                            // Quick up = tap; a claimed swipe or leaving the icon =
                                            // abandon; still hold = pick up, lifting from where the
                                            // finger is when the hold completes.
                                            val press = awaitIconPress(down)
                                            if (press is IconPressOutcome.Tap) {
                                                onAppClick(currentPlaced.app)
                                                return@awaitEachGesture
                                            }
                                            if (press !is IconPressOutcome.Held) return@awaitEachGesture
                                            val holdPos = press.position
                                            // Desktop locked: long-press does nothing (no menu, no drag); tap still launches.
                                            if (pageLocked) return@awaitEachGesture
                                            // PICK UP — lift into the shared controller (not yet
                                            // "moving"); refused while another finger holds a lift.
                                            val token = Any()
                                            val pickup = Offset(
                                                placed.cellX * cellW + holdPos.x,
                                                placed.cellY * cellH + holdPos.y,
                                            )
                                            if (!dragController.start(
                                                    currentPlaced.app,
                                                    DragSource.Home,
                                                    dragController.gridBounds.topLeft + pickup,
                                                    token,
                                                )
                                            ) return@awaitEachGesture
                                            dragging = placed
                                            dragPos = pickup
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            // The menu opens now, under the still-held finger (issue #12);
                                            // pulling the icon on closes it (DismissWhenDragMoves in
                                            // HomeScreen) and the drag goes on. Anchor at the icon's TOP
                                            // edge when it's in the lower half (popup flips above) and its
                                            // BOTTOM edge otherwise, so the popup never covers the icon;
                                            // only icons in the top ~45% open downward, so a tall popup
                                            // never covers the dock.
                                            run {
                                                val cellTop = dragController.gridBounds.top + placed.cellY * cellH
                                                val above = cellTop + cellH / 2f > windowHeightPx * 0.45f
                                                val anchorX = dragController.gridBounds.left + placed.cellX * cellW + cellW / 2f
                                                val anchorY = if (above) cellTop else cellTop + cellH
                                                onAppMenu(
                                                    currentPlaced.app,
                                                    IntOffset(anchorX.roundToInt(), anchorY.roundToInt()),
                                                    above,
                                                )
                                            }
                                            val dragStartPx = IconPress.DragStartThreshold.toPx()
                                            try {
                                                // DRAG — dragPointer reports every pixel, so a lifted icon only
                                                // starts moving once pulled past the drag-start threshold;
                                                // a hold that merely wobbles still releases into the menu
                                                // instead of nudging the icon into a neighbouring cell.
                                                val completed = dragPointer(down.id) { change ->
                                                    val delta = change.positionChange()
                                                    change.consume()
                                                    if (!dragController.owns(token)) return@dragPointer
                                                    dragPos += delta
                                                    if (!dragController.moving &&
                                                        IconPress.startsDrag(change.position, holdPos, dragStartPx)
                                                    ) {
                                                        dragController.beginMove()
                                                    }
                                                    if (!dragController.moving) return@dragPointer
                                                    dragController.update(
                                                        dragController.gridBounds.topLeft + dragPos,
                                                    )
                                                    // Track the cell the icon would land in.
                                                    val cx = (dragPos.x / cellW).toInt()
                                                        .coerceIn(0, columns - 1)
                                                    val cy = (dragPos.y / cellH).toInt()
                                                        .coerceIn(0, rows - 1)
                                                    val nc = IntOffset(cx, cy)
                                                    if (nc != targetCell) targetCell = nc
                                                }
                                                // DROP — only act on a real finger-up (completed),
                                                // never on a cancellation, so the menu can't pop up
                                                // under a still-pressed finger.
                                                // Nothing on release once HOME cancelled the lift.
                                                val d = dragging?.takeIf { dragController.owns(token) }
                                                // A still release leaves the menu, opened at the lift, as it is.
                                                if (d != null && completed && dragController.moving) {
                                                    val rootPos = dragController.gridBounds.topLeft + dragPos
                                                    when {
                                                        // Dropped on the top "remove" zone → take it off home.
                                                        dragController.isOverRemove(rootPos) -> {
                                                            removedKeys = removedKeys + d.app.key
                                                            onRemoveFromHome(d.app)
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        }
                                                        // Cross-surface: dropped on the dock — between
                                                        // tiles while it has room, or onto a tile (a
                                                        // folder with that app, or into that folder).
                                                        dragController.isOverDock(rootPos) -> {
                                                            if (dragController.dockAccepts(rootPos)) {
                                                                // Hide from home until the flow drops it.
                                                                removedKeys = removedKeys + d.app.key
                                                                onDropOnDock(d.app, dragController.dockTargetAt(rootPos))
                                                                haptics.performHapticFeedback(
                                                                    HapticFeedbackType.LongPress,
                                                                )
                                                            }
                                                            // Dock full → reject: icon stays on home.
                                                        }

                                                        else -> {
                                                            val tx = (dragPos.x / cellW).toInt()
                                                                .coerceIn(0, columns - 1)
                                                            val ty = (dragPos.y / cellH).toInt()
                                                                .coerceIn(0, rows - 1)
                                                            // The page arriving under the finger, even
                                                            // while an edge flip is still animating.
                                                            val targetPage = pagerState.targetPage
                                                            val occupant = latestEntries.firstOrNull {
                                                                it.page == targetPage && it.cellX == tx && it.cellY == ty &&
                                                                    !(it is PlacedApp && it.app.key == d.app.key)
                                                            }
                                                            when (occupant) {
                                                                // Drop on another app → create a folder.
                                                                is PlacedApp -> {
                                                                    removedKeys = removedKeys + d.app.key
                                                                    onCreateFolder(occupant.app, d.app)
                                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                }
                                                                // Drop on a folder → add the app to it.
                                                                is PlacedFolder -> {
                                                                    removedKeys = removedKeys + d.app.key
                                                                    onAddToFolder(d.app, occupant.id)
                                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                }
                                                                // Free cell → move (optimistic; roll back if rejected).
                                                                else -> {
                                                                    optimistic = optimistic + (d.app.key to Triple(targetPage, tx, ty))
                                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                    scope.launch {
                                                                        if (!onMove(d.app, targetPage, tx, ty)) {
                                                                            optimistic = optimistic - d.app.key
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } finally {
                                                // Reset even on cancellation (node disposed / pointerInput
                                                // restarted), mirroring the local-drag path below, so a dead
                                                // gesture can't leave the shared controller lifted.
                                                if (dragController.owns(token)) {
                                                    targetCell = null
                                                    dragging = null
                                                }
                                                dragController.stop(token)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                AppIcon(
                                    appItem = placed.app,
                                    labelColor = labelColor,
                                    showLabel = showLabels,
                                    iconSize = cellIconSize,
                                    launchOrigin = launchOrigin,
                                    // Fixed centred cell: reserve the full label block so a name that
                                    // wraps does not lift its icon above its neighbours'.
                                    reserveLabelLines = true,
                                    badgeCount = badges[placed.app.badgeKey] ?: 0,
                                    badgeShowCount = badgeShowCount,
                                    badgeScale = badgeScale,
                                )
                            }
                            }
                            is PlacedShortcut -> {
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            val sx = entry.cellX.coerceIn(0, columns - 1)
                                            val sy = entry.cellY.coerceIn(0, rows - 1)
                                            IntOffset((sx * cellW).roundToInt(), (sy * cellH).roundToInt())
                                        }
                                        .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
                                        .graphicsLayer {
                                            // A row just added by another app pops in (see revealScale).
                                            val reveal = revealScale[entry.rowId]
                                            if (reveal != null) {
                                                scaleX = reveal
                                                scaleY = reveal
                                            }
                                            alpha = when {
                                                (draggingLocal as? PlacedShortcut)?.rowId == entry.rowId && localMoving -> 0f
                                                reveal != null -> reveal.coerceIn(0f, 1f)
                                                else -> 1f
                                            }
                                        }
                                        // Removable: drag up to the "Poista" zone to take it off home
                                        // (no in-place menu — the remove zone replaces it).
                                        .localEntryDrag(
                                            entry = entry,
                                            rowId = entry.rowId,
                                            removable = true,
                                            onTap = { onLaunchShortcut(entry) },
                                            onStillPress = {},
                                            onRemove = { onRemoveShortcut(entry.rowId) },
                                            pageLocked = pageLocked,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ShortcutIconContent(entry, showLabels, labelColor, cellIconSize)
                                }
                            }
                            is PlacedWidget -> {
                                val widget = entry
                                val ctx = LocalContext.current
                                val info = remember(widget.appWidgetId, widgetProviderGeneration) {
                                    AppWidgetManager.getInstance(ctx).getAppWidgetInfo(widget.appWidgetId)
                                }
                                // The persistent host view (created + kept alive in HomeScreen so its
                                // AppWidgetHost listener survives page scrolling) and its scrollability —
                                // the root bounds are published to the controller so the swipe-up detector
                                // yields to a collection widget's own scroll instead of opening the drawer.
                                val hostView = widgetViews[widget.appWidgetId]
                                val widgetScrollable = widgetScrollableById[widget.appWidgetId] ?: false
                                var widgetRect by remember(widget.rowId) { mutableStateOf(Rect.Zero) }
                                // Page 0 can be composed twice (the real page and the copy at the tail):
                                // its host views and its scroll-bail rect belong to the instance on
                                // screen (WorkspaceWrap.kt); every other page owns its widgets. Ownership
                                // is read where it is used — this flow, the position callback, the host
                                // view's update — never as a composed value: a prefetched page can be
                                // composed with copyOnScreen still stale and is not recomposed when it
                                // flips. The copy keeps its rect under its own key (row ids are positive),
                                // so neither instance can drop the other's.
                                val rectKey = if (isTailCopy) -widget.rowId else widget.rowId
                                LaunchedEffect(rectKey, widgetScrollable) {
                                    snapshotFlow {
                                        widgetScrollable && ownsWidgetViews(page, isTailCopy, copyOnScreen) &&
                                            !widgetRect.isEmpty
                                    }.collect { register ->
                                        if (register) {
                                            dragController.scrollableWidgetRects[rectKey] = widgetRect
                                        } else {
                                            dragController.scrollableWidgetRects.remove(rectKey)
                                        }
                                    }
                                }
                                DisposableEffect(rectKey) {
                                    onDispose { dragController.scrollableWidgetRects.remove(rectKey) }
                                }
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(
                                                (widget.cellX.coerceIn(0, columns - 1) * cellW).roundToInt(),
                                                (widget.cellY.coerceIn(0, rows - 1) * cellH).roundToInt(),
                                            )
                                        }
                                        .size(
                                            with(density) { (widget.spanX * cellW).toDp() },
                                            with(density) { (widget.spanY * cellH).toDp() },
                                        )
                                        .onGloballyPositioned {
                                            widgetRect = it.boundsInRoot()
                                            if (widgetScrollable && ownsWidgetViews(page, isTailCopy, copyOnScreen)) {
                                                dragController.scrollableWidgetRects[rectKey] = widgetRect
                                            }
                                        }
                                        .graphicsLayer {
                                            // A row another app's pin request just added pops in (see revealScale).
                                            val reveal = revealScale[widget.rowId]
                                            if (reveal != null) {
                                                scaleX = reveal
                                                scaleY = reveal
                                            }
                                            alpha = when {
                                                widgetDragController.moving &&
                                                    widgetDragController.active?.item?.rowId == widget.rowId -> 0f
                                                reveal != null -> reveal.coerceIn(0f, 1f)
                                                else -> 1f
                                            }
                                        }
                                        .widgetDragGesture(widget.rowId, !pageLocked, widgetDragController) { root, fraction, owner ->
                                            liftWidget(EditingItem(widget.rowId, widget.appWidgetId, widget.page,
                                                widget.cellX, widget.cellY, widget.spanX, widget.spanY), root, fraction, owner)
                                        }
                                        // Last, so every geometry read above (bounds, drag, reveal) still sees the
                                        // whole span and only the hosted view is inset (widgetInset).
                                        .padding(widgetInsetDp),
                                ) {
                                    if (hostView != null) {
                                        // Host the persistent AppWidgetHostView inside a container, re-parenting
                                        // it into this page's container whenever the page (re)composes. The view
                                        // object — and its AppWidgetHost listener — is never destroyed by a page
                                        // scroll, so a collection widget's deferred row updates aren't dropped.
                                        AndroidView(
                                            factory = { c -> android.widget.FrameLayout(c) },
                                            update = { container ->
                                                val insetDp = widgetInsetDp.value.toInt()
                                                val wDp = insetWidgetSizeDp((widget.spanX * cellW / density.density).toInt(), insetDp)
                                                val hDp = insetWidgetSizeDp((widget.spanY * cellH / density.density).toInt(), insetDp)
                                                if (wDp > 0 && hDp > 0) {
                                                    widgetSizeReporter.report(widget.appWidgetId, wDp, hDp) {
                                                        sendWidgetSize(hostView, wDp, hDp)
                                                    }
                                                }
                                                // Only the instance on screen hosts the view. Read on every
                                                // run: the update observes copyOnScreen itself, so a flip
                                                // re-runs this block in both instances of page 0.
                                                val owns = ownsWidgetViews(page, isTailCopy, copyOnScreen)
                                                if (owns && hostView.parent !== container) {
                                                    (hostView.parent as? android.view.ViewGroup)?.removeView(hostView)
                                                    container.removeAllViews()
                                                    container.addView(
                                                        hostView,
                                                        android.widget.FrameLayout.LayoutParams(
                                                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                                        ),
                                                    )
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else if (info == null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                stringResource(R.string.widget_unavailable),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    // else: the host view is still being created (brief) — render nothing.
                                }
                            }
                            is PendingWidget -> {
                                val pending = entry
                                val ctxP = LocalContext.current
                                // Resolve the provider's label + icon (falls back to the app icon). The
                                // system caches these, so a plain remember keyed on the provider is fine.
                                val visual = remember(pending.provider) {
                                    val info = AppWidgetManager.getInstance(ctxP).installedProviders
                                        .firstOrNull { it.provider == pending.provider }
                                    val label = info?.loadLabel(ctxP.packageManager)
                                        ?: pending.provider.packageName
                                    val icon = runCatching {
                                        (info?.loadIcon(ctxP, ctxP.resources.displayMetrics.densityDpi)
                                            ?: ctxP.packageManager.getApplicationIcon(pending.provider.packageName))
                                            .toBitmap().asImageBitmap()
                                    }.getOrNull()
                                    label to icon
                                }
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(
                                                (pending.cellX.coerceIn(0, columns - 1) * cellW).roundToInt(),
                                                (pending.cellY.coerceIn(0, rows - 1) * cellH).roundToInt(),
                                            )
                                        }
                                        .size(
                                            with(density) { (pending.spanX * cellW).toDp() },
                                            with(density) { (pending.spanY * cellH).toDp() },
                                        )
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                        .pointerInput(pending.rowId, pageLocked) {
                                            if (pageLocked) return@pointerInput
                                            detectTapGestures(
                                                onTap = { onRestorePendingWidget(pending) },
                                                onLongPress = { onRemovePendingWidget(pending.rowId) },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(8.dp),
                                    ) {
                                        visual.second?.let { bmp ->
                                            Image(bmp, contentDescription = null, modifier = Modifier.size(36.dp))
                                            Spacer(Modifier.height(6.dp))
                                        }
                                        Text(
                                            visual.first.toString(),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            stringResource(R.string.widget_restore_tap),
                                            color = Color.White.copy(alpha = 0.85f),
                                            style = MaterialTheme.typography.labelSmall,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                            is PlacedBuiltin -> {
                                val space = entry
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(
                                                (space.cellX.coerceIn(0, columns - 1) * cellW).roundToInt(),
                                                (space.cellY.coerceIn(0, rows - 1) * cellH).roundToInt(),
                                            )
                                        }
                                        .size(
                                            with(density) { (space.spanX * cellW).toDp() },
                                            with(density) { (space.spanY * cellH).toDp() },
                                        )
                                        .graphicsLayer {
                                            alpha = if (widgetDragController.moving &&
                                                widgetDragController.active?.item?.rowId == space.rowId) 0f else 1f
                                        }
                                        .widgetDragGesture(space.rowId, !pageLocked, widgetDragController) { root, fraction, owner ->
                                            liftWidget(EditingItem(space.rowId, null, space.page,
                                                space.cellX, space.cellY, space.spanX, space.spanY), root, fraction, owner)
                                        },
                                ) {
                                    CompositionLocalProvider(LocalWidgetDragController provides widgetDragController) {
                                        when (space.type) {
                                            HomeItemEntity.BUILTIN_NOTIFICATIONS ->
                                                NotificationsWidget(modifier = Modifier.fillMaxSize())
                                            HomeItemEntity.BUILTIN_BATTERY ->
                                                BatteryWidget(modifier = Modifier.fillMaxSize())
                                            HomeItemEntity.BUILTIN_PEOPLE ->
                                                PeopleWidget(modifier = Modifier.fillMaxSize())
                                            else -> SmartspaceWidget(modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                            }
                            }
                        }
                    val ew = editingWidget
                    if (ew != null && ew.page == page) {
                        val ctxE = LocalContext.current
                        // A built-in widget (appWidgetId == null) has no provider info: it resizes freely
                        // within the grid (with a sane minimum width) and has nothing to reconfigure.
                        val info = remember(ew.appWidgetId, widgetProviderGeneration) {
                            ew.appWidgetId?.let { AppWidgetManager.getInstance(ctxE).getAppWidgetInfo(it) }
                        }
                        // A built-in's min/default spans depend on its type (the notifications row is
                        // usable narrower than the smartspace clock).
                        val builtinType = remember(ew.rowId) {
                            if (ew.appWidgetId == null) builtins.firstOrNull { it.rowId == ew.rowId }?.type else null
                        }
                        val range = remember(ew.rowId, columns, rows, cellW, cellH) {
                            if (ew.appWidgetId == null) {
                                val minSpanX = when (builtinType) {
                                    HomeItemEntity.BUILTIN_NOTIFICATIONS -> NOTIFICATIONS_MIN_SPAN_X
                                    HomeItemEntity.BUILTIN_BATTERY -> BATTERY_SPAN
                                    HomeItemEntity.BUILTIN_PEOPLE -> PEOPLE_MIN_SPAN_X
                                    else -> SMARTSPACE_MIN_SPAN_X
                                }
                                WidgetResizeRange(
                                    minX = minSpanX.coerceAtMost(columns), minY = 1,
                                    maxX = columns, maxY = rows,
                                    horizontal = true, vertical = true,
                                )
                            } else {
                                info?.let { widgetResizeRange(it, ctxE, columns, rows, cellW / density.density, cellH / density.density) }?.takeIf { it.isResizable }
                            }
                        }
                        val reconfigurable = remember(ew.appWidgetId) { info?.let { isReconfigurableWidget(it) } ?: false }
                        key(ew) { Box(Modifier.fillMaxSize().graphicsLayer {
                            alpha = if (widgetDragController.moving) 0f else 1f
                        }) { WidgetEditOverlay(
                            widget = ew,
                            widgetDragController = widgetDragController,
                            commitScope = scope,
                            onCancelPreview = { widgetOptimistic = null },
                            onLift = ::liftWidget,
                            range = range,
                            reconfigurable = reconfigurable,
                            cellW = cellW, cellH = cellH, columns = columns, rows = rows, density = density,
                            // A resize "fits" if the cells are free OR the overlapping items can be pushed
                            // aside (same plan the repository commits on release), so the handle only grows
                            // when the commit will actually succeed.
                            canFit = { x, y, sx, sy ->
                                rectFreeOnGrid(ew.rowId, ew.page, x, y, sx, sy) ||
                                    ReorderPlanner.planFit(
                                        effectiveEntries.mapIndexed { i, e ->
                                            val (esx, esy) = when (e) {
                                                is PlacedWidget -> e.spanX to e.spanY
                                                is PendingWidget -> e.spanX to e.spanY
                                                is PlacedBuiltin -> e.spanX to e.spanY
                                                else -> 1 to 1
                                            }
                                            // The edited row must carry its REAL id so planFit can exclude
                                            // it from its own collision set (widget or built-in alike).
                                            val erow = when (e) {
                                                is PlacedWidget -> e.rowId
                                                is PlacedBuiltin -> e.rowId
                                                else -> -(i.toLong() + 1)
                                            }
                                            ReorderPlanner.Rect(erow, e.page, e.cellX, e.cellY, esx, esy)
                                        },
                                        ew.rowId, ew.page, x, y, sx, sy, columns, rows,
                                    ) != null
                            },
                            // Optimistic like the app-drag path: the new bounds show immediately (the
                            // hosted content too, not just the edit frame) and a repository rejection
                            // rolls the override back; the overlay also resets its frame on `false`.
                            onSetBounds = { x, y, sx, sy ->
                                widgetOptimistic = ew.rowId to WidgetBounds(ew.page, x, y, sx, sy)
                                val ok = onSetWidgetBounds(ew.rowId, ew.page, x, y, sx, sy)
                                if (!ok) widgetOptimistic = null
                                ok
                            },
                            // Size-only preview while a resize handle is mid-drag: no DB write yet, the
                            // commit (or the overlay's reset) lands on release.
                            onPreviewBounds = { x, y, sx, sy ->
                                widgetOptimistic = ew.rowId to WidgetBounds(ew.page, x, y, sx, sy)
                            },
                            onReconfigure = { ew.appWidgetId?.let(onReconfigureWidget); editingWidget = null },
                            onExit = { editingWidget = null },
                        ) } }
                    }
                }
            }

            if (showPageIndicator && pageCount > 1) {
                PageDots(
                    count = pageCount,
                    current = dotForPagerPage(pagerState.currentPage, pageCount, tailCopy),
                    home = homeIndex,
                    homeMarker = homePageMarker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = PAGE_DOTS_PADDING),
                )
            }
        }
        // Just above the page dots (or where they would be): touch-transparent text.
        PageEndHint(
            bounces = { pageEndBounces },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        )
        WidgetDragLayer(widgetDragController, dragController, cellW, cellH)
        // The floating dragged *app icon* is drawn by LauncherShell (above the workspace, dock and the
        // drawer) so it can travel across surfaces. A dragged folder/shortcut never leaves the grid, so
        // its floating preview is drawn here locally, following the finger ([localDragPos], grid coords).
        // A folder's copy is the host's (HomeScreen draws it above the dock too, where this one would
        // be covered right at the drop zone); a shortcut never leaves the grid, so its copy stays here.
        val dl = draggingLocal?.takeUnless { it is PlacedFolder }
        if (dl != null && localMoving) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (localDragPos.x - cellW / 2f).roundToInt(),
                            (localDragPos.y - cellH / 2f).roundToInt(),
                        )
                    }
                    .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
                    .graphicsLayer {
                        alpha = 0.92f
                        scaleX = 1.1f
                        scaleY = 1.1f
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (dl) {
                    is PlacedFolder -> FolderIcon(
                        name = dl.name,
                        apps = dl.apps,
                        showLabel = showLabels,
                        showName = showFolderNames,
                        badgeCount = dl.apps.sumOf { badges[it.badgeKey] ?: 0 },
                        badgeShowCount = badgeShowCount,
                        badgeScale = badgeScale,
                        labelColor = labelColor,
                        size = cellIconSize,
                        customIcon = dl.icon,
                    )
                    is PlacedShortcut -> ShortcutIconContent(dl, showLabels, labelColor, cellIconSize)
                    else -> Unit
                }
            }
        }
    }
}

/** Launcher3-style widget edit frame: a touch-consuming scrim (so the resize gesture can't leak into
 *  the drawer swipe-up or page scroll), a body-drag layer (move within the page, or drop on the top
 *  Remove pill to delete), a border, edge handles on the resizable axes (each edge moves, opposite edge
 *  fixed, 0.66-cell hysteresis snap, clamped to provider min/max + grid + a free-rect check, committed
 *  on release), and a gear (reconfigure) button. */
@Composable
private fun WidgetEditOverlay(
    widget: EditingItem,
    widgetDragController: WidgetDragController,
    onLift: (EditingItem, Offset, Offset, Any) -> Unit,
    commitScope: kotlinx.coroutines.CoroutineScope,
    onCancelPreview: () -> Unit,
    range: WidgetResizeRange?,
    reconfigurable: Boolean,
    cellW: Float,
    cellH: Float,
    columns: Int,
    rows: Int,
    density: androidx.compose.ui.unit.Density,
    canFit: (x: Int, y: Int, spanX: Int, spanY: Int) -> Boolean,
    onSetBounds: suspend (x: Int, y: Int, spanX: Int, spanY: Int) -> Boolean,
    onPreviewBounds: (x: Int, y: Int, spanX: Int, spanY: Int) -> Unit,
    onReconfigure: () -> Unit,
    onExit: () -> Unit,
) {
    var cx by remember(widget.rowId) { mutableStateOf(widget.cellX) }
    var cy by remember(widget.rowId) { mutableStateOf(widget.cellY) }
    var sx by remember(widget.rowId) { mutableStateOf(widget.spanX) }
    var sy by remember(widget.rowId) { mutableStateOf(widget.spanY) }
    // Last bounds the repository ACCEPTED. A rejected commit (a collision canFit's plan couldn't
    // foresee) resets the frame here — before this, the overlay silently kept showing the rejected
    // geometry while the widget itself stayed put.
    var okX by remember(widget.rowId) { mutableStateOf(widget.cellX) }
    var okY by remember(widget.rowId) { mutableStateOf(widget.cellY) }
    var okSx by remember(widget.rowId) { mutableStateOf(widget.spanX) }
    var okSy by remember(widget.rowId) { mutableStateOf(widget.spanY) }
    // The gesture blocks below are keyed on the row id, so they outlive the recompositions an edit
    // itself causes (a resize pushes icons aside → new entries). Read the collision checks through
    // state: the captured lambdas kept judging moves/resizes against the layout at edit-mode entry.
    val currentCanFit by rememberUpdatedState(canFit)
    var previewing by remember(widget.rowId) { mutableStateOf(false) }
    val cancelPreview by rememberUpdatedState(onCancelPreview)
    DisposableEffect(widget.rowId) {
        onDispose { if (previewing) cancelPreview() }
    }
    var committing by remember(widget.rowId) { mutableStateOf(false) }
    val currentSetBounds by rememberUpdatedState(onSetBounds)
    val currentPreview by rememberUpdatedState(onPreviewBounds)
    fun showPreview(x: Int, y: Int, w: Int, h: Int) {
        previewing = true
        currentPreview(x, y, w, h)
    }
    var blocked by remember(widget.rowId) { mutableStateOf(false) }
    fun preview(x: Int, y: Int, w: Int, h: Int): Boolean {
        val accepted = currentCanFit(x, y, w, h)
        blocked = !accepted
        return accepted
    }
    fun commitBounds() {
        if (committing) return
        val x = cx; val y = cy; val w = sx; val h = sy
        committing = true
        previewing = false
        commitScope.launch {
            try {
                if (currentSetBounds(x, y, w, h)) {
                    okX = x; okY = y; okSx = w; okSy = h
                } else {
                    cx = okX; cy = okY; sx = okSx; sy = okSy
                    currentPreview(cx, cy, sx, sy)
                }
            } finally { committing = false }
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val handlePx = with(density) { 48.dp.toPx() }
    val widthLabel = stringResource(R.string.widget_resize_width)
    val heightLabel = stringResource(R.string.widget_resize_height)
    val sizeLabel = stringResource(R.string.widget_size, sx, sy)
    fun resizeBy(dx: Int, dy: Int): Boolean {
        val limits = range ?: return false
        if (committing || (dx != 0 && !limits.horizontal) || (dy != 0 && !limits.vertical)) return false
        val w = if (dx == 0) sx else (sx + dx).coerceIn(limits.minX, limits.maxX)
        val h = if (dy == 0) sy else (sy + dy).coerceIn(limits.minY, limits.maxY)
        if ((w == sx && h == sy) || !preview(cx, cy, w, h)) return false
        sx = w; sy = h
        showPreview(cx, cy, sx, sy)
        commitBounds()
        return true
    }

    // Scrim: consumes a tap (exit) and, by being the topmost interactive layer, keeps the resize-handle
    // drags from ever reaching the root swipe-up detector or the pager.
    Box(Modifier.fillMaxSize().pointerInput(widget.rowId) { detectTapGestures { onExit() } })

    Box(
        Modifier.offset { IntOffset((cx * cellW).roundToInt(), (cy * cellH).roundToInt()) }
            .size(with(density) { (sx * cellW).toDp() }, with(density) { (sy * cellH).toDp() })
            .border(2.dp, if (blocked) MaterialTheme.colorScheme.error else primary, RoundedCornerShape(24.dp))
            .semantics {
                stateDescription = sizeLabel
                customActions = buildList {
                    if (range?.horizontal == true) {
                        add(CustomAccessibilityAction(widthLabel + " +") { resizeBy(1, 0) })
                        add(CustomAccessibilityAction(widthLabel + " −") { resizeBy(-1, 0) })
                    }
                    if (range?.vertical == true) {
                        add(CustomAccessibilityAction(heightLabel + " +") { resizeBy(0, 1) })
                        add(CustomAccessibilityAction(heightLabel + " −") { resizeBy(0, -1) })
                    }
                }
            }
            .widgetDragGesture(widget.rowId, !committing, widgetDragController, immediate = true) { root, fraction, owner ->
                onLift(widget.copy(cellX = cx, cellY = cy, spanX = sx, spanY = sy), root, fraction, owner)
            },
    )
    if (range != null) {
        val left = cx * cellW; val top = cy * cellH; val right = (cx + sx) * cellW; val bottom = (cy + sy) * cellH
        var accX by remember(widget.rowId) { mutableStateOf(0f) }
        var accY by remember(widget.rowId) { mutableStateOf(0f) }
        // 0.66-cell hysteresis (Launcher3 getSpanIncrement): a step fires only past 66% of a cell.
        fun step(acc: Float, cell: Float): Int { val f = acc / cell; return if (kotlin.math.abs(f) > 0.66f) f.roundToInt() else 0 }
        fun hMod(centerX: Float, centerY: Float, label: String, onDrag: (Offset) -> Unit) = Modifier
            .offset { IntOffset((centerX - handlePx / 2).roundToInt(), (centerY - handlePx / 2).roundToInt()) }
            .size(with(density) { handlePx.toDp() })
            .drawBehind { drawCircle(primary, radius = 7.dp.toPx()) }
            .semantics { contentDescription = label }
            .pointerInput(widget.rowId, cellW, cellH, range, committing) {
                if (committing) return@pointerInput
                detectDragGestures(
                    onDragEnd = { accX = 0f; accY = 0f; blocked = false; commitBounds() },
                    onDragCancel = {
                        accX = 0f; accY = 0f; blocked = false
                        cx = okX; cy = okY; sx = okSx; sy = okSy
                        previewing = false
                        cancelPreview()
                    },
                ) { ch, d -> ch.consume(); onDrag(d) }
            }
        // Each accepted step previews the new bounds into the workspace (onPreviewBounds), so the
        // hosted widget CONTENT resizes with the frame mid-drag instead of jumping on release.
        if (range.horizontal) {
            Box(hMod(right, (top + bottom) / 2f, widthLabel) { d ->
                accX += d.x
                val s = step(accX, cellW)
                if (s != 0) {
                    val n = (sx + s).coerceIn(range.minX, range.maxX)
                    if (n != sx && preview(cx, cy, n, sy)) { sx = n; showPreview(cx, cy, sx, sy) }
                    accX = 0f
                }
            })
            Box(hMod(left, (top + bottom) / 2f, widthLabel) { d ->
                accX += d.x
                val s = step(accX, cellW)
                if (s != 0) {
                    resizeWidgetStartEdge(cx, sx, s, range.minX, range.maxX)?.let { (x, width) ->
                        if ((x != cx || width != sx) && preview(x, cy, width, sy)) {
                            cx = x; sx = width
                            showPreview(cx, cy, sx, sy)
                        }
                    }
                    accX = 0f
                }
            })
        }
        if (range.vertical) {
            Box(hMod((left + right) / 2f, bottom, heightLabel) { d ->
                accY += d.y
                val s = step(accY, cellH)
                if (s != 0) {
                    val n = (sy + s).coerceIn(range.minY, range.maxY)
                    if (n != sy && preview(cx, cy, sx, n)) { sy = n; showPreview(cx, cy, sx, sy) }
                    accY = 0f
                }
            })
            Box(hMod((left + right) / 2f, top, heightLabel) { d ->
                accY += d.y
                val s = step(accY, cellH)
                if (s != 0) {
                    resizeWidgetStartEdge(cy, sy, s, range.minY, range.maxY)?.let { (y, height) ->
                        if ((y != cy || height != sy) && preview(cx, y, sx, height)) {
                            cy = y; sy = height
                            showPreview(cx, cy, sx, sy)
                        }
                    }
                    accY = 0f
                }
            })
        }
    }

    if (reconfigurable) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        ((cx + sx) * cellW - handlePx - 8.dp.toPx()).roundToInt(),
                        (cy * cellH + 8.dp.toPx()).roundToInt()
                    )
                }
                .size(with(density) { handlePx.toDp() })
                .background(primary, CircleShape)
                .clickable(enabled = !committing) { onReconfigure() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_home_settings),
                contentDescription = stringResource(R.string.widget_reconfigure),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(with(density) { (handlePx * 0.6f).toDp() }),
            )
        }
    }
}

/**
 * Launcher3's disabled-icon look (iconloaderlib `FastBitmapDrawable.getDisabledColorFilter`, Apache-2.0):
 * brightened halfway to white, then fully desaturated.
 */
private val DisabledShortcutFilter: ColorFilter = run {
    val brightness = android.graphics.ColorMatrix().apply {
        val m = array
        m[0] = 0.5f; m[6] = 0.5f; m[12] = 0.5f
        m[4] = 127f; m[9] = 127f; m[14] = 127f
    }
    val filter = android.graphics.ColorMatrix().apply {
        setSaturation(0f)
        preConcat(brightness)
    }
    ColorFilter.colorMatrix(ColorMatrix(filter.array))
}

/** The icon + label of a pinned deep shortcut (the gesture, menu and offset live at the call site);
 *  greyed out while the app has it disabled — a tap then shows why. */
@Composable
internal fun ShortcutIconContent(
    shortcut: PlacedShortcut,
    showLabel: Boolean,
    labelColor: Color = Color.White,
    iconSize: Dp = 52.dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val icon = shortcut.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = shortcut.label,
                modifier = Modifier.size(iconSize),
                colorFilter = if (shortcut.enabled) null else DisabledShortcutFilter,
            )
        } else {
            Box(Modifier.size(iconSize))
        }
        if (showLabel) AppLabel(shortcut.label, labelColor, reserveLines = true)
    }
}

/**
 * The size-options bundle Launcher3 builds for a hosted widget: min/max width/height in dp plus (on
 * Android S+) the list of supported sizes. Collection widgets — a RemoteViewsService-backed
 * ListView/GridView/StackView such as WhatsApp's conversation list — render EMPTY when handed an empty
 * options bundle (the old `Bundle.EMPTY`), because the RemoteViewsService can't size its list. Adapted
 * from AOSP Launcher3 `WidgetSizes.getWidgetSizeOptions` (Apache-2.0).
 */
internal fun widgetSizeOptions(wDp: Int, hDp: Int): android.os.Bundle =
    android.os.Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        // Tell the provider this widget is on the HOME screen (not keyguard). Some providers — notably
        // WhatsApp's chat-list collection — refuse to populate ("pending config activity") until the
        // host category is known. Launcher3 sets this too.
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
            android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            putParcelableArrayList(
                AppWidgetManager.OPTION_APPWIDGET_SIZES,
                arrayListOf(android.util.SizeF(wDp.toFloat(), hDp.toFloat())),
            )
        }
    }

/**
 * Sends [hostView]'s provider its new size as ONE options update. On S+ the size-list overload keeps
 * the [android.util.SizeF] list that responsive widgets pick their layout from (Launcher3 uses it too);
 * the old min/max overload rewrote that list to empty inside the bundle it was handed, and re-sending
 * the same bundle with updateAppWidgetOptions broadcast every change twice.
 */
internal fun sendWidgetSize(hostView: android.appwidget.AppWidgetHostView, wDp: Int, hDp: Int) {
    val opts = widgetSizeOptions(wDp, hDp)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        hostView.updateAppWidgetSize(opts, listOf(android.util.SizeF(wDp.toFloat(), hDp.toFloat())))
    } else {
        @Suppress("DEPRECATION")
        hostView.updateAppWidgetSize(opts, wDp, hDp, wDp, hDp)
    }
}

/**
 * True if this view tree contains an internally scrollable collection view — an `AdapterView`
 * (ListView / GridView / StackView / AdapterViewFlipper) or a ScrollView. Mirrors AOSP Launcher3
 * `LauncherAppWidgetHostView.checkScrollableRecursively` (Apache-2.0); used to let a collection widget
 * keep its own touch-scroll instead of the home swipe-up detector stealing the vertical drag.
 */
internal fun android.view.View.containsScrollableCollection(): Boolean = when (this) {
    is android.widget.AdapterView<*> -> true
    is android.widget.ScrollView -> true
    is android.widget.HorizontalScrollView -> true
    is android.view.ViewGroup -> (0 until childCount).any { getChildAt(it).containsScrollableCollection() }
    else -> false
}

internal const val PAGE_DOT_TAG = "page_dot"
internal const val PAGE_DOT_HOUSE_TAG = "page_dot_house"

/** The page dots. The home page is marked so the page HOME returns to is visible at a glance: a ring
 *  around its dot, or (LauncherSettings.MARKER_HOUSE) a house glyph in place of the dot. Either way
 *  it takes the current/inactive colour like the plain dots. */
@Composable
internal fun PageDots(
    count: Int,
    current: Int,
    home: Int,
    homeMarker: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val color = if (index == current) Color.White else Color.White.copy(alpha = 0.4f)
            val isHome = index == home
            if (isHome && homeMarker == LauncherSettings.MARKER_HOUSE) {
                Icon(
                    painter = painterResource(LauncherIcons.Home),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(PAGE_DOT_HOUSE_SIZE).pageDotHalo().testTag(PAGE_DOT_HOUSE_TAG),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(if (isHome) PAGE_DOT_HOME_SIZE else PAGE_DOT_SIZE)
                        .pageDotHalo()
                        .then(if (isHome) Modifier.border(1.5.dp, color, CircleShape).padding(2.dp) else Modifier)
                        .background(color = color, shape = CircleShape)
                        .testTag(PAGE_DOT_TAG),
                )
            }
        }
    }
}

/** A soft dark halo behind a page dot or the house, so the white markers read on a light wallpaper
 *  too — the page-dot twin of the widgets' text shadow. On a dark wallpaper it melts into it. */
private fun Modifier.pageDotHalo(): Modifier = drawBehind {
    val radius = size.minDimension / 2f + PAGE_DOT_HALO_EXTRA.toPx()
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = PAGE_DOT_HALO_ALPHA),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private val PAGE_DOT_HALO_EXTRA = 5.dp
private const val PAGE_DOT_HALO_ALPHA = 0.5f

private val PAGE_DOT_SIZE = 7.dp
private val PAGE_DOT_HOME_SIZE = 9.dp
private val PAGE_DOT_HOUSE_SIZE = 13.dp
private val PAGE_DOTS_PADDING = 8.dp

/** The height of the page dots' row below the grid: the home page's marker is its tallest. */
internal fun pageDotsRowHeight(homeMarker: String): Dp =
    PAGE_DOTS_PADDING * 2 + if (homeMarker == LauncherSettings.MARKER_HOUSE) PAGE_DOT_HOUSE_SIZE else PAGE_DOT_HOME_SIZE

/** Widget bounds applied optimistically (by row id) until the DB flow catches up — spans included,
 *  so an edit-frame resize shows on the hosted content immediately, not only after the round-trip. */
private data class WidgetBounds(val page: Int, val cellX: Int, val cellY: Int, val spanX: Int, val spanY: Int)

/** How long an icon dropped into a folder or the dock stays hidden while its write lands. */
private const val REMOVED_HIDE_TIMEOUT_MS = 1_500L

/** How long a dragged item must rest at a screen edge before the page flips. */
private const val EDGE_FLIP_DWELL_MS = 550L

/** How long the reveal of a new item waits for its row to reach the grid before giving it up. */
private const val NEW_ITEM_WAIT_MS = 3_000L

/** A beat between the page turn and the first pop, so the pop is seen on a resting page. */
private const val NEW_ITEM_POP_PAUSE_MS = 150L

/** The pop-in of a new item, as Launcher3's `ItemInstallQueue` bounce (Apache-2.0): 450 ms, each
 *  item 85 ms after the previous, with an overshoot of tension 1.3. */
private const val NEW_ITEM_POP_MS = 450
private const val NEW_ITEM_STAGGER_MS = 85
private const val NEW_ITEM_OVERSHOOT_TENSION = 1.3f

/** The platform's `OvershootInterpolator` as a Compose easing: runs past 1 and settles back. */
private val NewItemOvershoot = Easing { t ->
    val x = t - 1f
    x * x * ((NEW_ITEM_OVERSHOOT_TENSION + 1f) * x + NEW_ITEM_OVERSHOOT_TENSION) + 1f
}

/** The home_items row behind an entry, for everything that is addressed by row (apps aren't). */
private val HomeEntry.rowIdOrNull: Long?
    get() = when (this) {
        is PlacedApp -> null
        is PlacedFolder -> id
        is PlacedShortcut -> rowId
        is PlacedWidget -> rowId
        is PendingWidget -> rowId
        is PlacedBuiltin -> rowId
    }

/** A stable composition key per home item. Apps have no row id here; their app key is unique on home. */
private fun homeEntryKey(entry: HomeEntry): String = when (entry) {
    is PlacedApp -> "app:${entry.app.key}"
    is PlacedFolder -> "folder:${entry.id}"
    is PlacedShortcut -> "shortcut:${entry.rowId}"
    is PlacedWidget -> "widget:${entry.rowId}"
    is PendingWidget -> "pending:${entry.rowId}"
    is PlacedBuiltin -> "builtin:${entry.rowId}"
}

/** True when something on [page] covers ([cellX], [cellY]): an icon, a folder, a shortcut, or a widget's footprint. */
internal fun cellTaken(entries: List<HomeEntry>, page: Int, cellX: Int, cellY: Int): Boolean =
    entries.any { e ->
        e.page == page && when (e) {
            is PlacedWidget -> cellX in e.cellX until e.cellX + e.spanX && cellY in e.cellY until e.cellY + e.spanY
            is PendingWidget -> cellX in e.cellX until e.cellX + e.spanX && cellY in e.cellY until e.cellY + e.spanY
            is PlacedBuiltin -> cellX in e.cellX until e.cellX + e.spanX && cellY in e.cellY until e.cellY + e.spanY
            else -> e.cellX == cellX && e.cellY == cellY
        }
    }
