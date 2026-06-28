package org.arkikeskus.launcher.feature.home

import android.appwidget.AppWidgetManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.component.AppIcon
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
 * [homeSignals] (HOME pressed / home gesture) always scrolls back to the first page.
 */
@Composable
fun Workspace(
    pageCount: Int,
    columns: Int,
    rows: Int,
    entries: List<HomeEntry>,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    showPageIndicator: Boolean,
    locked: Boolean,
    homeSignals: Flow<Unit>,
    dragController: HomeDragController,
    onAppClick: (AppItem) -> Unit,
    onAppMenu: (AppItem, IntOffset, Boolean) -> Unit,
    onMove: suspend (AppItem, Int, Int, Int) -> Boolean,
    onMoveFolder: suspend (Long, Int, Int, Int) -> Boolean,
    onMoveToDock: (AppItem, Int) -> Unit,
    onRemoveFromHome: (AppItem) -> Unit,
    onOpenFolder: (PlacedFolder) -> Unit,
    onLaunchShortcut: (PlacedShortcut) -> Unit,
    onRemoveShortcut: (Long) -> Unit,
    onCreateFolder: (target: AppItem, dropped: AppItem) -> Unit,
    onAddToFolder: (app: AppItem, folderId: Long) -> Unit,
    onEmptyAreaMenu: (IntOffset, Boolean) -> Unit,
    onRemoveWidget: (PlacedWidget) -> Unit = {},
    onSetWidgetBounds: suspend (rowId: Long, page: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> Boolean =
        { _, _, _, _, _, _ -> false },
    onReconfigureWidget: (appWidgetId: Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val windowHeightPx = LocalWindowInfo.current.containerSize.height

    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf<PlacedApp?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragDistance by remember { mutableStateOf(0f) }
    var editingWidget by remember { mutableStateOf<PlacedWidget?>(null) }
    // While the edit frame is open, the root swipe-up detector must yield (it bails on localGestureActive),
    // so a handle/scrim drag can never open the drawer. Reset when the frame closes.
    LaunchedEffect(editingWidget) { dragController.localGestureActive = editingWidget != null }
    var widgetOptimistic by remember { mutableStateOf<Pair<Long, Triple<Int, Int, Int>>?>(null) }

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
    val pagerState = rememberPagerState(pageCount = { pageCount + 1 })
    // The cell the dragged icon would drop into — shown as a placeholder while dragging.
    var targetCell by remember { mutableStateOf<IntOffset?>(null) }

    val placedApps = remember(entries) { entries.filterIsInstance<PlacedApp>() }
    val folders = remember(entries) { entries.filterIsInstance<PlacedFolder>() }
    val placedShortcuts = remember(entries) { entries.filterIsInstance<PlacedShortcut>() }
    val placedWidgets = remember(entries) { entries.filterIsInstance<PlacedWidget>() }
    val effectiveWidgets = remember(placedWidgets, widgetOptimistic) {
        val opt = widgetOptimistic
        if (opt == null) placedWidgets else placedWidgets.map { w ->
            if (w.rowId == opt.first) w.copy(page = opt.second.first, cellX = opt.second.second, cellY = opt.second.third) else w
        }
    }
    // clear the optimistic override once the DB flow reports the widget at its new cell
    LaunchedEffect(placedWidgets) {
        val opt = widgetOptimistic ?: return@LaunchedEffect
        if (placedWidgets.any { it.rowId == opt.first && it.page == opt.second.first && it.cellX == opt.second.second && it.cellY == opt.second.third }) {
            widgetOptimistic = null
        }
    }

    // Optimistic placements (key -> page/cellX/cellY) applied on top of [placedApps] until the
    // database flow catches up, so an icon doesn't flash at its old cell for a frame on drop.
    var optimistic by remember { mutableStateOf(emptyMap<String, Triple<Int, Int, Int>>()) }
    // Keys optimistically removed from home (dragged into the dock or a folder) — hidden until the
    // flow drops them, so the icon doesn't reappear at its old cell for a frame.
    var removedKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(placedApps) {
        if (optimistic.isNotEmpty()) {
            optimistic = optimistic.filterNot { (key, pos) ->
                placedApps.any {
                    it.app.key == key && it.page == pos.first &&
                        it.cellX == pos.second && it.cellY == pos.third
                }
            }
        }
        if (removedKeys.isNotEmpty()) {
            removedKeys = removedKeys.filterTo(mutableSetOf()) { rk -> placedApps.any { it.app.key == rk } }
        }
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
    LaunchedEffect(folders, placedShortcuts) {
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
    val effectiveEntries: List<HomeEntry> = remember(effectiveApps, effectiveFolders, effectiveShortcuts, effectiveWidgets) {
        effectiveApps + effectiveFolders + effectiveShortcuts + effectiveWidgets
    }
    // The drag gesture's pointerInput block outlives recomposition (its keys don't include the entry
    // list), so it must read the *latest* placements through this state, not a stale closure capture.
    val latestEntries by rememberUpdatedState(effectiveEntries)

    val cellW = if (columns > 0 && gridSize.width > 0) gridSize.width.toFloat() / columns else 1f
    val cellH = if (rows > 0 && gridSize.height > 0) gridSize.height.toFloat() / rows else 1f
    val moveThresholdPx = with(density) { 16.dp.toPx() }
    // Page flips only when the dragged icon is pushed right against the screen edge.
    val edgePx = with(density) { 20.dp.toPx() }

    // Client-side mirror of rectFitsForRow over the rendered entries, for the live drag placeholder.
    fun rectFreeOnGrid(excludeRowId: Long, page: Int, x: Int, y: Int, spanX: Int, spanY: Int): Boolean {
        if (x < 0 || y < 0 || x + spanX > columns || y + spanY > rows) return false
        val occupied = HashSet<Triple<Int, Int, Int>>()
        for (e in effectiveEntries) {
            val (ex, ey) = when (e) { is PlacedWidget -> e.spanX to e.spanY; else -> 1 to 1 }
            val erow = when (e) { is PlacedWidget -> e.rowId; else -> -2L }
            if (erow == excludeRowId) continue
            for (dx in 0 until ex) for (dy in 0 until ey) occupied += Triple(e.page, e.cellX + dx, e.cellY + dy)
        }
        for (dx in 0 until spanX) for (dy in 0 until spanY) if (Triple(page, x + dx, y + dy) in occupied) return false
        return true
    }

    // Shared drag for a local home entry (folder or pinned shortcut): long-press lifts; drag moves it
    // by [rowId] to a cell (swapping any occupant); a tap is [onTap]; a still long-press is
    // [onStillPress] (open folder / show shortcut menu). Mirrors the app drag but stays on the grid.
    fun Modifier.localEntryDrag(
        entry: HomeEntry,
        rowId: Long,
        removable: Boolean,
        onTap: () -> Unit,
        onStillPress: () -> Unit,
        onRemove: () -> Unit,
    ): Modifier = this.pointerInput(rowId, entry.page, entry.cellX, entry.cellY, cellW, cellH, columns, rows, pageCount, locked) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val slop = viewConfiguration.touchSlop
            var tapped = false
            var swiped = false
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (c == null) { swiped = true; return@withTimeoutOrNull }
                    c.consume()
                    if (!c.pressed) { tapped = true; return@withTimeoutOrNull }
                    if ((c.position - down.position).getDistance() > slop) { swiped = true; return@withTimeoutOrNull }
                }
            }
            if (tapped) { onTap(); return@awaitEachGesture }
            if (swiped) return@awaitEachGesture
            // Desktop locked: long-press does nothing (no move/remove); tap (above) still opens.
            if (locked) return@awaitEachGesture
            // PICK UP
            draggingLocal = entry
            localMoving = false
            // Claim the gesture for this local entry so the root swipe-up detector (which runs in the
            // Initial pass, before us) won't steal the first movement after this long-press.
            dragController.localGestureActive = true
            localDragPos = Offset(entry.cellX * cellW + down.position.x, entry.cellY * cellH + down.position.y)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                val completed = drag(down.id) { change ->
                    val delta = change.positionChange()
                    change.consume()
                    localDragPos += delta
                    if (!localMoving) {
                        localMoving = true
                        if (removable) dragController.localDragging = true
                    }
                    // Publish root coords so the remove zone can highlight + hit-test this local drag.
                    if (removable) dragController.update(dragController.gridBounds.topLeft + localDragPos)
                    if (!pagerState.isScrollInProgress) {
                        if (localDragPos.x > gridSize.width - edgePx && pagerState.currentPage < pageCount) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else if (localDragPos.x < edgePx && pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }
                    }
                    val cx = (localDragPos.x / cellW).toInt().coerceIn(0, columns - 1)
                    val cy = (localDragPos.y / cellH).toInt().coerceIn(0, rows - 1)
                    val nc = IntOffset(cx, cy)
                    if (nc != targetCell) targetCell = nc
                }
                targetCell = null
                val rootPos = dragController.gridBounds.topLeft + localDragPos
                if (completed && localMoving && removable && dragController.isOverRemove(rootPos)) {
                    onRemove()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } else if (completed && localMoving) {
                    val tx = (localDragPos.x / cellW).toInt().coerceIn(0, columns - 1)
                    val ty = (localDragPos.y / cellH).toInt().coerceIn(0, rows - 1)
                    val targetPage = pagerState.currentPage
                    if (targetPage != entry.page || tx != entry.cellX || ty != entry.cellY) {
                        localOptimistic = rowId to Triple(targetPage, tx, ty)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            if (!onMoveFolder(rowId, targetPage, tx, ty)) localOptimistic = null
                        }
                    }
                } else if (completed) {
                    onStillPress()
                }
            } finally {
                // Reset even on cancellation (node disposed / pointerInput restarted), so a stuck flag
                // can never leave the root swipe detector permanently disabled.
                dragController.localDragging = false
                dragController.localGestureActive = false
                draggingLocal = null
                localMoving = false
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = editingWidget != null) { editingWidget = null }

    // HOME button / home gesture: always return to the first page.
    LaunchedEffect(homeSignals, pageCount) {
        homeSignals.collect {
            editingWidget = null
            if (pagerState.currentPage != 0) pagerState.animateScrollToPage(0)
        }
    }

    // Snap back off the always-present trailing page if it settles there empty (so the extra page
    // never feels like a real second page until an icon is dropped onto it).
    val settledPage = pagerState.settledPage
    LaunchedEffect(settledPage, dragging, draggingLocal) {
        if (dragging == null && draggingLocal == null && settledPage > 0 && effectiveEntries.none { it.page == settledPage }) {
            val lastContent = effectiveEntries.maxOfOrNull { it.page } ?: 0
            if (settledPage > lastContent) pagerState.animateScrollToPage(lastContent)
        }
    }

    // Publish the grid metrics so a dock→home drop can map the finger's root position to a cell.
    LaunchedEffect(columns, rows) {
        dragController.columns = columns
        dragController.rows = rows
    }
    LaunchedEffect(pagerState, pageCount) {
        snapshotFlow { pagerState.currentPage }
            .collect { dragController.currentPage = it.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) }
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = dragging == null && draggingLocal == null && editingWidget == null,
                // While dragging, keep every page composed so flipping to another page can't
                // dispose the dragged item's node (which would cancel the in-progress drag).
                beyondViewportPageCount = if (dragging != null || draggingLocal != null) pageCount.coerceAtLeast(0) else 0,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { gridSize = it }
                    .onGloballyPositioned { dragController.gridBounds = it.boundsInRoot() },
            ) { page ->
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
                                // Local relocation (folder or pinned shortcut) — stays on the grid.
                                draggingLocal != null && localMoving -> targetCell
                                // Our own home drag: hide the cell hint while hovering the dock.
                                dragging != null && dragController.moving -> {
                                    if (dragController.isOverDock(dragController.rootPosition)) null else targetCell
                                }
                                dragController.moving &&
                                    (dragController.source == DragSource.Dock ||
                                        dragController.source == DragSource.Drawer) &&
                                    dragController.isOverGrid(dragController.rootPosition) -> {
                                    val (_, cx, cy) = dragController.cellAt(dragController.rootPosition)
                                    IntOffset(cx, cy)
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
                            // pointer) always wins and suppresses this.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = true)
                                var resolved = false
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
                                            resolved = true
                                        }
                                    }
                                }
                                // Fired only on a still empty-area hold. If an icon picked up
                                // (dragging != null) the hold belonged to that icon, not settings.
                                // Also suppressed while a widget is in edit mode (editingWidget != null):
                                // a still dwell on the widget during a move/remove drag must not also
                                // open the home-options menu underneath the edit scrim.
                                if (held == null && !resolved && dragging == null && editingWidget == null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // Anchor the options popup at the press point (root coords); flip it
                                    // above when the press is in the lower half of the screen.
                                    val anchorPt = dragController.gridBounds.topLeft + down.position
                                    onEmptyAreaMenu(
                                        IntOffset(anchorPt.x.roundToInt(), anchorPt.y.roundToInt()),
                                        anchorPt.y > windowHeightPx * 0.45f,
                                    )
                                }
                            }
                        },
                ) {
                    effectiveEntries.asSequence()
                        .filter { it.page == page }
                        .forEach { entry ->
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
                                            removable = false,
                                            onTap = { onOpenFolder(entry) },
                                            onStillPress = { onOpenFolder(entry) },
                                            onRemove = {},
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    FolderIcon(
                                        name = entry.name,
                                        apps = entry.apps,
                                        showLabel = showLabels,
                                        badgeCount = folderBadge,
                                        badgeShowCount = badgeShowCount,
                                        badgeScale = badgeScale,
                                    )
                                }
                            }
                            is PlacedApp -> {
                            val placed = entry
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
                                    .pointerInput(
                                        placed.app.key, placed.page, placed.cellX, placed.cellY,
                                        cellW, cellH, columns, rows, pageCount, locked,
                                    ) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            // Claim the gesture immediately: an icon touch belongs
                                            // to the icon, so the pager and the page swipes can
                                            // never steal it (not even from finger jitter during the
                                            // long-press hold). Swipes for drawer/notifications start
                                            // on empty space.
                                            down.consume()
                                            val slop = viewConfiguration.touchSlop
                                            // Quick up = tap; movement = abandon (drag needs a
                                            // long-press); still hold = pick up to drag.
                                            var tapped = false
                                            var swiped = false
                                            withTimeoutOrNull(
                                                viewConfiguration.longPressTimeoutMillis,
                                            ) {
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val c = ev.changes.firstOrNull { it.id == down.id }
                                                    if (c == null) {
                                                        swiped = true
                                                        return@withTimeoutOrNull
                                                    }
                                                    c.consume()
                                                    if (!c.pressed) {
                                                        tapped = true
                                                        return@withTimeoutOrNull
                                                    }
                                                    if ((c.position - down.position).getDistance() > slop) {
                                                        swiped = true
                                                        return@withTimeoutOrNull
                                                    }
                                                }
                                            }
                                            if (tapped) {
                                                onAppClick(placed.app)
                                                return@awaitEachGesture
                                            }
                                            if (swiped) return@awaitEachGesture
                                            // Desktop locked: long-press does nothing (no menu, no drag); tap still launches.
                                            if (locked) return@awaitEachGesture
                                            // PICK UP
                                            dragging = placed
                                            dragDistance = 0f
                                            dragPos = Offset(
                                                placed.cellX * cellW + down.position.x,
                                                placed.cellY * cellH + down.position.y,
                                            )
                                            // Lift into the shared controller (not yet "moving").
                                            dragController.start(
                                                placed.app,
                                                DragSource.Home,
                                                dragController.gridBounds.topLeft + dragPos,
                                            )
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            // DRAG
                                            val completed = drag(down.id) { change ->
                                                val delta = change.positionChange()
                                                change.consume()
                                                dragPos += delta
                                                dragDistance += delta.getDistance()
                                                // Any movement past the touch slop (drag() already
                                                // enforces it) is a drag → start moving immediately so
                                                // the floating icon tracks the finger from the start.
                                                if (!dragController.moving) dragController.beginMove()
                                                dragController.update(
                                                    dragController.gridBounds.topLeft + dragPos,
                                                )
                                                if (!pagerState.isScrollInProgress) {
                                                    if (dragPos.x > gridSize.width - edgePx &&
                                                        pagerState.currentPage < pageCount
                                                    ) {
                                                        scope.launch {
                                                            pagerState.animateScrollToPage(
                                                                pagerState.currentPage + 1,
                                                            )
                                                        }
                                                    } else if (dragPos.x < edgePx &&
                                                        pagerState.currentPage > 0
                                                    ) {
                                                        scope.launch {
                                                            pagerState.animateScrollToPage(
                                                                pagerState.currentPage - 1,
                                                            )
                                                        }
                                                    }
                                                }
                                                // Track the cell the icon would land in.
                                                val cx = (dragPos.x / cellW).toInt()
                                                    .coerceIn(0, columns - 1)
                                                val cy = (dragPos.y / cellH).toInt()
                                                    .coerceIn(0, rows - 1)
                                                val nc = IntOffset(cx, cy)
                                                if (nc != targetCell) targetCell = nc
                                            }
                                            targetCell = null
                                            // DROP — only act on a real finger-up (completed),
                                            // never on a cancellation, so the menu can't pop up
                                            // under a still-pressed finger.
                                            val d = dragging
                                            if (d != null && completed && !dragController.moving) {
                                                // Static long-press → show the menu next to the icon
                                                // (after release, so it can't steal the drag). Anchor at
                                                // the icon's TOP edge when it's in the lower half (popup
                                                // flips above) and its BOTTOM edge otherwise (popup
                                                // below) — so the popup never covers the icon.
                                                val cellTop = dragController.gridBounds.top + d.cellY * cellH
                                                // Bias toward opening upward: only icons in the top
                                                // ~45% open downward (room below); the middle and
                                                // everything lower open up so a tall popup never
                                                // covers the dock.
                                                val above = cellTop + cellH / 2f > windowHeightPx * 0.45f
                                                val anchorX = dragController.gridBounds.left + d.cellX * cellW + cellW / 2f
                                                val anchorY = if (above) cellTop else cellTop + cellH
                                                onAppMenu(
                                                    d.app,
                                                    IntOffset(anchorX.roundToInt(), anchorY.roundToInt()),
                                                    above,
                                                )
                                            } else if (d != null && completed && dragController.moving) {
                                                val rootPos = dragController.gridBounds.topLeft + dragPos
                                                when {
                                                    // Dropped on the top "remove" zone → take it off home.
                                                    dragController.isOverRemove(rootPos) -> {
                                                        removedKeys = removedKeys + d.app.key
                                                        onRemoveFromHome(d.app)
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                    // Cross-surface: dropped on the dock.
                                                    dragController.isOverDock(rootPos) -> {
                                                        if (dragController.dockHasSpace) {
                                                            // Hide from home until the flow drops it.
                                                            removedKeys = removedKeys + d.app.key
                                                            onMoveToDock(
                                                                d.app,
                                                                dragController.dockIndexAt(rootPos),
                                                            )
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
                                                        val targetPage = pagerState.currentPage
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
                                            dragController.stop()
                                            dragging = null
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                AppIcon(
                                    appItem = placed.app,
                                    labelColor = Color.White,
                                    showLabel = showLabels,
                                    iconSize = 52.dp,
                                    maxLabelLines = 1,
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
                                            alpha = if ((draggingLocal as? PlacedShortcut)?.rowId == entry.rowId && localMoving) 0f else 1f
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
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ShortcutIconContent(entry, showLabels)
                                }
                            }
                            is PlacedWidget -> {
                                val widget = entry
                                val ctx = LocalContext.current
                                val host = LocalAppWidgetHost.current
                                val info = remember(widget.appWidgetId) {
                                    AppWidgetManager.getInstance(ctx).getAppWidgetInfo(widget.appWidgetId)
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
                                        .pointerInput(widget.rowId, widget.page, widget.cellX, widget.cellY, widget.spanX, widget.spanY, locked, cellW, cellH, columns, rows, pageCount) {
                                            if (locked) return@pointerInput
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                val slop = viewConfiguration.touchSlop
                                                var movedEarly = false
                                                val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                                    while (true) {
                                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                                        val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                                        if (!c.pressed) return@withTimeoutOrNull false
                                                        if ((c.position - down.position).getDistance() > slop) { movedEarly = true; return@withTimeoutOrNull false }
                                                    }
                                                    @Suppress("UNREACHABLE_CODE") false
                                                }
                                                // held == null → timed out while still pressed (and not moved) → PICK UP.
                                                if (held != null || movedEarly) return@awaitEachGesture
                                                // held long-press → enter edit mode. Consume the REST of this
                                                // gesture (until the finger lifts) so the embedded
                                                // AppWidgetHostView receives a CANCEL instead of an UP and does
                                                // not also launch the widget's own click action.
                                                editingWidget = widget
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                while (true) {
                                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                                    ev.changes.forEach { it.consume() }
                                                    if (ev.changes.none { it.id == down.id && it.pressed }) break
                                                }
                                                return@awaitEachGesture
                                            }
                                        },
                                ) {
                                    if (info != null && host != null) {
                                        AndroidView(
                                            factory = { c -> host.createView(c.applicationContext, widget.appWidgetId, info) },
                                            update = { hostView ->
                                                val wDp = (widget.spanX * cellW / density.density).toInt()
                                                val hDp = (widget.spanY * cellH / density.density).toInt()
                                                runCatching { hostView.updateAppWidgetSize(android.os.Bundle.EMPTY, wDp, hDp, wDp, hDp) }
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
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
                                }
                            }
                            }
                        }
                    val ew = editingWidget
                    if (ew != null && ew.page == page) {
                        val ctxE = LocalContext.current
                        val info = remember(ew.appWidgetId) { AppWidgetManager.getInstance(ctxE).getAppWidgetInfo(ew.appWidgetId) }
                        val range = remember(ew.appWidgetId) { info?.let { widgetResizeRange(it, ctxE, columns) }?.takeIf { it.isResizable } }
                        val reconfigurable = remember(ew.appWidgetId) { info?.let { isReconfigurableWidget(it) } ?: false }
                        WidgetEditOverlay(
                            widget = ew,
                            range = range,
                            reconfigurable = reconfigurable,
                            cellW = cellW, cellH = cellH, columns = columns, rows = rows, density = density,
                            rectFree = { x, y, sx, sy -> rectFreeOnGrid(ew.rowId, ew.page, x, y, sx, sy) },
                            onSetBounds = { x, y, sx, sy -> scope.launch { onSetWidgetBounds(ew.rowId, ew.page, x, y, sx, sy) } },
                            onReconfigure = { onReconfigureWidget(ew.appWidgetId); editingWidget = null },
                            onExit = { editingWidget = null },
                            onRemove = { onRemoveWidget(ew); editingWidget = null },
                            onMove = { x, y, spanX, spanY ->
                                        widgetOptimistic = ew.rowId to Triple(ew.page, x, y)
                                        scope.launch {
                                            if (!onSetWidgetBounds(ew.rowId, ew.page, x, y, spanX, spanY)) widgetOptimistic = null
                                        }
                                    },
                            dragController = dragController,
                        )
                    }
                }
            }

            if (showPageIndicator && pageCount > 1) {
                PageDots(
                    count = pageCount,
                    current = pagerState.currentPage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }
        // The floating dragged *app icon* is drawn by LauncherShell (above the workspace, dock and the
        // drawer) so it can travel across surfaces. A dragged folder/shortcut never leaves the grid, so
        // its floating preview is drawn here locally, following the finger ([localDragPos], grid coords).
        val dl = draggingLocal
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
                        badgeCount = dl.apps.sumOf { badges[it.badgeKey] ?: 0 },
                        badgeShowCount = badgeShowCount,
                        badgeScale = badgeScale,
                    )
                    is PlacedShortcut -> ShortcutIconContent(dl, showLabels)
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
    widget: PlacedWidget,
    range: WidgetResizeRange?,
    reconfigurable: Boolean,
    cellW: Float,
    cellH: Float,
    columns: Int,
    rows: Int,
    density: androidx.compose.ui.unit.Density,
    rectFree: (x: Int, y: Int, spanX: Int, spanY: Int) -> Boolean,
    onSetBounds: (x: Int, y: Int, spanX: Int, spanY: Int) -> Unit,
    onReconfigure: () -> Unit,
    onExit: () -> Unit,
    dragController: HomeDragController,
    onRemove: () -> Unit,
    onMove: (x: Int, y: Int, spanX: Int, spanY: Int) -> Unit,
) {
    var cx by remember(widget.rowId) { mutableStateOf(widget.cellX) }
    var cy by remember(widget.rowId) { mutableStateOf(widget.cellY) }
    var sx by remember(widget.rowId) { mutableStateOf(widget.spanX) }
    var sy by remember(widget.rowId) { mutableStateOf(widget.spanY) }
    val primary = MaterialTheme.colorScheme.primary
    val handlePx = with(density) { 28.dp.toPx() }

    // Scrim: consumes a tap (exit) and, by being the topmost interactive layer, keeps the resize-handle
    // drags from ever reaching the root swipe-up detector or the pager.
    Box(Modifier.fillMaxSize().pointerInput(widget.rowId) { detectTapGestures { onExit() } })

    // Body drag: a drag that starts on the widget body (not a handle/gear, which sit on top and consume
    // first) moves the widget; dropping over the top Remove pill deletes it.
    var dragTopLeft by remember(widget.rowId) { mutableStateOf(Offset(cx * cellW, cy * cellH)) }
    // Finger offset within the widget at grab time. The Remove hit-test (and the remove-zone highlight)
    // must track the FINGER, not the widget's top-left: a tall widget grabbed mid-body would otherwise
    // never reach the top Remove pill (its top-left stays below it), so it could never be removed — it
    // just slid under the pill. Mirrors the app drag path (which adds down.position to the cell origin).
    var grabOffset by remember(widget.rowId) { mutableStateOf(Offset.Zero) }
    var dragging by remember(widget.rowId) { mutableStateOf(false) }
    var targetCell by remember(widget.rowId) { mutableStateOf<IntOffset?>(IntOffset(cx, cy)) }
    Box(
        modifier = Modifier
            .offset { IntOffset((cx * cellW).roundToInt(), (cy * cellH).roundToInt()) }
            .size(with(density) { (sx * cellW).toDp() }, with(density) { (sy * cellH).toDp() })
            .pointerInput(widget.rowId, cellW, cellH, columns, rows) {
                detectDragGestures(
                    onDragStart = { start ->
                        dragging = true
                        dragTopLeft = Offset(cx * cellW, cy * cellH)
                        grabOffset = start
                        dragController.localDragging = true
                    },
                    onDragEnd = {
                        dragging = false
                        dragController.localDragging = false
                        val rootPos = dragController.gridBounds.topLeft + dragTopLeft + grabOffset
                        if (dragController.isOverRemove(rootPos)) {
                            onRemove()
                        } else {
                            val t = targetCell
                            if (t != null && (t.x != cx || t.y != cy)) { onMove(t.x, t.y, sx, sy); cx = t.x; cy = t.y }
                        }
                    },
                    onDragCancel = { dragging = false; dragController.localDragging = false },
                ) { ch, d ->
                    ch.consume()
                    dragTopLeft += d
                    dragController.update(dragController.gridBounds.topLeft + dragTopLeft + grabOffset)
                    val tx = (dragTopLeft.x / cellW).roundToInt().coerceIn(0, (columns - sx).coerceAtLeast(0))
                    val ty = (dragTopLeft.y / cellH).roundToInt().coerceIn(0, (rows - sy).coerceAtLeast(0))
                    targetCell = if (rectFree(tx, ty, sx, sy)) IntOffset(tx, ty) else null
                }
            },
    )
    // Live move feedback: a highlighted placeholder at the target cell while body-dragging.
    val tgt = targetCell
    if (dragging && tgt != null) {
        Box(
            modifier = Modifier
                .offset { IntOffset((tgt.x * cellW).roundToInt(), (tgt.y * cellH).roundToInt()) }
                .size(with(density) { (sx * cellW).toDp() }, with(density) { (sy * cellH).toDp() })
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
        )
    }

    // Candidate-rect border. While body-dragging it follows the finger CONTINUOUSLY: the offset reads
    // dragTopLeft in the layout phase, so it re-positions every frame without recomposition (smooth, like
    // Pixel); the per-cell snap target is shown by the placeholder above. A faint fill makes the moving
    // card readable.
    Box(
        modifier = Modifier
            .offset {
                if (dragging) IntOffset(dragTopLeft.x.roundToInt(), dragTopLeft.y.roundToInt())
                else IntOffset((cx * cellW).roundToInt(), (cy * cellH).roundToInt())
            }
            .size(with(density) { (sx * cellW).toDp() }, with(density) { (sy * cellH).toDp() })
            .then(if (dragging) Modifier.background(primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)) else Modifier)
            .border(2.dp, primary, RoundedCornerShape(12.dp)),
    )

    if (range != null) {
        val left = cx * cellW; val top = cy * cellH; val right = (cx + sx) * cellW; val bottom = (cy + sy) * cellH
        var accX by remember(widget.rowId) { mutableStateOf(0f) }
        var accY by remember(widget.rowId) { mutableStateOf(0f) }
        // 0.66-cell hysteresis (Launcher3 getSpanIncrement): a step fires only past 66% of a cell.
        fun step(acc: Float, cell: Float): Int { val f = acc / cell; return if (kotlin.math.abs(f) > 0.66f) f.roundToInt() else 0 }
        fun hMod(centerX: Float, centerY: Float, onDrag: (Offset) -> Unit) = Modifier
            .offset { IntOffset((centerX - handlePx / 2).roundToInt(), (centerY - handlePx / 2).roundToInt()) }
            .size(with(density) { handlePx.toDp() })
            .background(primary, CircleShape)
            .pointerInput(widget.rowId) {
                detectDragGestures(onDragEnd = { accX = 0f; accY = 0f; onSetBounds(cx, cy, sx, sy) }) { ch, d -> ch.consume(); onDrag(d) }
            }
        if (range.horizontal) {
            Box(hMod(right, (top + bottom) / 2f) { d ->
                accX += d.x
                val s = step(accX, cellW)
                if (s != 0) { val n = (sx + s).coerceIn(range.minX, range.maxX); if (n != sx && rectFree(cx, cy, n, sy)) sx = n; accX = 0f }
            })
            Box(hMod(left, (top + bottom) / 2f) { d ->
                accX += d.x
                val s = step(accX, cellW)
                if (s != 0) {
                    val rightEdge = cx + sx
                    val nCx = (cx + s).coerceIn(0, rightEdge - range.minX)
                    val nSx = (rightEdge - nCx).coerceIn(range.minX, range.maxX)
                    val fCx = rightEdge - nSx
                    if ((fCx != cx || nSx != sx) && rectFree(fCx, cy, nSx, sy)) { cx = fCx; sx = nSx }
                    accX = 0f
                }
            })
        }
        if (range.vertical) {
            Box(hMod((left + right) / 2f, bottom) { d ->
                accY += d.y
                val s = step(accY, cellH)
                if (s != 0) { val n = (sy + s).coerceIn(range.minY, range.maxY); if (n != sy && rectFree(cx, cy, sx, n)) sy = n; accY = 0f }
            })
            Box(hMod((left + right) / 2f, top) { d ->
                accY += d.y
                val s = step(accY, cellH)
                if (s != 0) {
                    val bottomEdge = cy + sy
                    val nCy = (cy + s).coerceIn(0, bottomEdge - range.minY)
                    val nSy = (bottomEdge - nCy).coerceIn(range.minY, range.maxY)
                    val fCy = bottomEdge - nSy
                    if ((fCy != cy || nSy != sy) && rectFree(cx, fCy, sx, nSy)) { cy = fCy; sy = nSy }
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
                .clickable { onReconfigure() },
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

/** The icon + label of a pinned deep shortcut (the gesture, menu and offset live at the call site). */
@Composable
private fun ShortcutIconContent(shortcut: PlacedShortcut, showLabel: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val icon = shortcut.icon
        if (icon != null) {
            Image(bitmap = icon, contentDescription = shortcut.label, modifier = Modifier.size(52.dp))
        } else {
            Box(Modifier.size(52.dp))
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = shortcut.label,
                color = Color.White,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (index == current) Color.White else Color.White.copy(alpha = 0.4f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}
