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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    var widgetMenu by remember { mutableStateOf<PlacedWidget?>(null) }
    var resizingWidget by remember { mutableStateOf<PlacedWidget?>(null) }
    var draggingWidget by remember { mutableStateOf<PlacedWidget?>(null) }
    var widgetDragTopLeft by remember { mutableStateOf(Offset.Zero) }       // px, the widget's top-left while dragging
    var widgetTargetCell by remember { mutableStateOf<IntOffset?>(null) }   // grid cell of the drag target (null = no fit)
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

    // HOME button / home gesture: always return to the first page.
    LaunchedEffect(homeSignals, pageCount) {
        homeSignals.collect {
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
                userScrollEnabled = dragging == null && draggingLocal == null,
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
                                if (held == null && !resolved && dragging == null) {
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
                                        .graphicsLayer { alpha = if (draggingWidget?.rowId == widget.rowId) 0.4f else 1f }
                                        .pointerInput(widget.rowId, locked, cellW, cellH, columns, rows, pageCount) {
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
                                                // PICK UP
                                                draggingWidget = widget
                                                dragController.localGestureActive = true
                                                widgetDragTopLeft = Offset(widget.cellX * cellW, widget.cellY * cellH)
                                                widgetTargetCell = IntOffset(widget.cellX, widget.cellY)
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                var moved = false
                                                try {
                                                    while (true) {
                                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                        val delta = c.positionChange()
                                                        c.consume()
                                                        if (!c.pressed) break
                                                        if (delta != Offset.Zero) {
                                                            moved = true
                                                            widgetDragTopLeft += delta
                                                            val tx = (widgetDragTopLeft.x / cellW).roundToInt().coerceIn(0, (columns - widget.spanX).coerceAtLeast(0))
                                                            val ty = (widgetDragTopLeft.y / cellH).roundToInt().coerceIn(0, (rows - widget.spanY).coerceAtLeast(0))
                                                            val page = pagerState.currentPage
                                                            widgetTargetCell = if (rectFreeOnGrid(widget.rowId, page, tx, ty, widget.spanX, widget.spanY)) IntOffset(tx, ty) else null
                                                            if (!pagerState.isScrollInProgress) {
                                                                if (widgetDragTopLeft.x > gridSize.width - edgePx && pagerState.currentPage < pageCount) {
                                                                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                                                } else if (widgetDragTopLeft.x < edgePx && pagerState.currentPage > 0) {
                                                                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    val target = widgetTargetCell
                                                    if (moved && target != null) {
                                                        val page = pagerState.currentPage
                                                        if (page != widget.page || target.x != widget.cellX || target.y != widget.cellY) {
                                                            widgetOptimistic = widget.rowId to Triple(page, target.x, target.y)
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            scope.launch {
                                                                if (!onSetWidgetBounds(widget.rowId, page, target.x, target.y, widget.spanX, widget.spanY)) widgetOptimistic = null
                                                            }
                                                        }
                                                    } else if (!moved) {
                                                        widgetMenu = widget   // still long-press → menu
                                                    }
                                                } finally {
                                                    draggingWidget = null
                                                    widgetTargetCell = null
                                                    dragController.localGestureActive = false
                                                }
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
                                    DropdownMenu(
                                        expanded = widgetMenu?.rowId == widget.rowId,
                                        onDismissRequest = { widgetMenu = null },
                                    ) {
                                        val ctxR = LocalContext.current
                                        val range = remember(widget.appWidgetId) {
                                            AppWidgetManager.getInstance(ctxR).getAppWidgetInfo(widget.appWidgetId)
                                                ?.let { widgetResizeRange(it, ctxR, columns) }
                                        }
                                        if (range?.isResizable == true) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.widget_resize)) },
                                                onClick = { resizingWidget = widget; widgetMenu = null },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.widget_remove)) },
                                            onClick = {
                                                onRemoveWidget(widget)
                                                widgetMenu = null
                                            },
                                        )
                                    }
                                }
                            }
                            }
                        }
                    val dw = draggingWidget
                    val wt = widgetTargetCell
                    if (dw != null && wt != null && page == pagerState.currentPage) {
                        Box(
                            modifier = Modifier
                                .offset { IntOffset((wt.x * cellW).roundToInt(), (wt.y * cellH).roundToInt()) }
                                .size(with(density) { (dw.spanX * cellW).toDp() }, with(density) { (dw.spanY * cellH).toDp() })
                                .padding(4.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                        )
                    }
                    val rw = resizingWidget
                    if (rw != null && rw.page == page) {
                        val ctxRz = LocalContext.current
                        val info = remember(rw.appWidgetId) { AppWidgetManager.getInstance(ctxRz).getAppWidgetInfo(rw.appWidgetId) }
                        val range = remember(rw.appWidgetId) { info?.let { widgetResizeRange(it, ctxRz, columns) } }
                        if (range != null) {
                            WidgetResizeOverlay(
                                widget = rw,
                                range = range,
                                cellW = cellW,
                                cellH = cellH,
                                columns = columns,
                                rows = rows,
                                density = density,
                                rectFree = { x, y, sx, sy -> rectFreeOnGrid(rw.rowId, rw.page, x, y, sx, sy) },
                                onCommit = { x, y, sx, sy ->
                                    resizingWidget = null
                                    scope.launch { onSetWidgetBounds(rw.rowId, rw.page, x, y, sx, sy) }
                                },
                            )
                        }
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

/** Edge-handle resize overlay: a scrim that commits on tap, the live candidate rect, and one handle
 *  per resizable edge. Each handle moves its own edge (opposite edge fixed), snapping to cells and
 *  clamped to the provider min/max, the grid, and a free-rectangle check. */
@Composable
private fun WidgetResizeOverlay(
    widget: PlacedWidget,
    range: WidgetResizeRange,
    cellW: Float,
    cellH: Float,
    columns: Int,
    rows: Int,
    density: androidx.compose.ui.unit.Density,
    rectFree: (x: Int, y: Int, spanX: Int, spanY: Int) -> Boolean,
    onCommit: (x: Int, y: Int, spanX: Int, spanY: Int) -> Unit,
) {
    var cx by remember { mutableStateOf(widget.cellX) }
    var cy by remember { mutableStateOf(widget.cellY) }
    var sx by remember { mutableStateOf(widget.spanX) }
    var sy by remember { mutableStateOf(widget.spanY) }
    // Full-grid scrim: a tap commits and exits.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onCommit(cx, cy, sx, sy) } },
    )
    // Candidate rect outline.
    Box(
        modifier = Modifier
            .offset { IntOffset((cx * cellW).roundToInt(), (cy * cellH).roundToInt()) }
            .size(with(density) { (sx * cellW).toDp() }, with(density) { (sy * cellH).toDp() })
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
    )
    val handle = with(density) { 28.dp.toPx() }
    val primaryColor = MaterialTheme.colorScheme.primary
    fun handleMod(centerX: Float, centerY: Float, onDrag: (Offset) -> Unit) = Modifier
        .offset { IntOffset((centerX - handle / 2).roundToInt(), (centerY - handle / 2).roundToInt()) }
        .size(with(density) { handle.toDp() })
        .background(primaryColor, CircleShape)
        .pointerInput(widget.rowId) { detectDragGestures { change, dragAmount -> change.consume(); onDrag(dragAmount) } }
    val left = cx * cellW; val top = cy * cellH; val right = (cx + sx) * cellW; val bottom = (cy + sy) * cellH
    var accX by remember { mutableStateOf(0f) }
    var accY by remember { mutableStateOf(0f) }
    if (range.horizontal) {
        // RIGHT handle: change sx (left edge fixed).
        Box(handleMod(right, (top + bottom) / 2f) { d ->
            accX += d.x
            val target = (((cx + sx) * cellW + accX) / cellW).roundToInt()
            val newSx = (target - cx).coerceIn(range.minX, range.maxX)
            if (newSx != sx && rectFree(cx, cy, newSx, sy)) { sx = newSx; accX = 0f }
        })
        // LEFT handle: move left edge, right edge (cx+sx) fixed.
        Box(handleMod(left, (top + bottom) / 2f) { d ->
            accX += d.x
            val rightEdge = cx + sx
            val target = ((cx * cellW + accX) / cellW).roundToInt().coerceIn(0, rightEdge - range.minX)
            val newSx = (rightEdge - target).coerceIn(range.minX, range.maxX)
            val newCx = rightEdge - newSx
            if ((newCx != cx || newSx != sx) && rectFree(newCx, cy, newSx, sy)) { cx = newCx; sx = newSx; accX = 0f }
        })
    }
    if (range.vertical) {
        // BOTTOM handle: change sy (top edge fixed).
        Box(handleMod((left + right) / 2f, bottom) { d ->
            accY += d.y
            val target = (((cy + sy) * cellH + accY) / cellH).roundToInt()
            val newSy = (target - cy).coerceIn(range.minY, range.maxY)
            if (newSy != sy && rectFree(cx, cy, sx, newSy)) { sy = newSy; accY = 0f }
        })
        // TOP handle: move top edge, bottom edge (cy+sy) fixed.
        Box(handleMod((left + right) / 2f, top) { d ->
            accY += d.y
            val bottomEdge = cy + sy
            val target = ((cy * cellH + accY) / cellH).roundToInt().coerceIn(0, bottomEdge - range.minY)
            val newSy = (bottomEdge - target).coerceIn(range.minY, range.maxY)
            val newCy = bottomEdge - newSy
            if ((newCy != cy || newSy != sy) && rectFree(cx, newCy, sx, newSy)) { cy = newCy; sy = newSy; accY = 0f }
        })
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
