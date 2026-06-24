package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The paged home workspace: a [HorizontalPager] of cell grids with a floating drag overlay.
 *
 * Gestures are separated by location so they never fight each other:
 * - **Icon** (child node): tap launches; long-press lifts the icon (haptic tick) and the drag
 *   consumes the pointer immediately, so neither the pager nor the page swipes can steal it. Drop
 *   on a cell to move it (a second tick); drop without moving opens [onAppMenu]; drag to the screen
 *   edge flips the page.
 * - **Page background** (parent node, only reached when no icon is under the finger): a vertical
 *   swipe opens the drawer / notifications; a still long-press opens settings.
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
    swipeUpForDrawer: Boolean,
    swipeDownForNotifications: Boolean,
    homeSignals: Flow<Unit>,
    dragController: HomeDragController,
    onAppClick: (AppItem) -> Unit,
    onAppMenu: (AppItem, IntOffset) -> Unit,
    onMove: suspend (AppItem, Int, Int, Int) -> Boolean,
    onMoveFolder: suspend (Long, Int, Int, Int) -> Boolean,
    onMoveToDock: (AppItem, Int) -> Unit,
    onRemoveFromHome: (AppItem) -> Unit,
    onOpenFolder: (PlacedFolder) -> Unit,
    onLaunchShortcut: (PlacedShortcut) -> Unit,
    onRemoveShortcut: (Long) -> Unit,
    onCreateFolder: (target: AppItem, dropped: AppItem) -> Unit,
    onAddToFolder: (app: AppItem, folderId: Long) -> Unit,
    onDrawerDrag: (Float) -> Unit,
    onDrawerSettle: (Float) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf<PlacedApp?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragDistance by remember { mutableStateOf(0f) }

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
    val effectiveEntries: List<HomeEntry> = remember(effectiveApps, effectiveFolders, effectiveShortcuts) {
        effectiveApps + effectiveFolders + effectiveShortcuts
    }
    // The drag gesture's pointerInput block outlives recomposition (its keys don't include the entry
    // list), so it must read the *latest* placements through this state, not a stale closure capture.
    val latestEntries by rememberUpdatedState(effectiveEntries)

    val cellW = if (columns > 0 && gridSize.width > 0) gridSize.width.toFloat() / columns else 1f
    val cellH = if (rows > 0 && gridSize.height > 0) gridSize.height.toFloat() / rows else 1f
    val moveThresholdPx = with(density) { 16.dp.toPx() }
    // Page flips only when the dragged icon is pushed right against the screen edge.
    val edgePx = with(density) { 20.dp.toPx() }

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
    ): Modifier = this.pointerInput(rowId, entry.page, entry.cellX, entry.cellY, cellW, cellH, columns, rows, pageCount) {
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
            // PICK UP
            draggingLocal = entry
            localMoving = false
            localDragPos = Offset(entry.cellX * cellW + down.position.x, entry.cellY * cellH + down.position.y)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
            dragController.localDragging = false
            draggingLocal = null
            localMoving = false
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
                        // Page background — only reached when no icon is under the finger. Swipe up
                        // drives the drawer (finger-following, via onDrawerDrag/Settle); swipe down
                        // opens notifications (one-shot).
                        .pointerInput(swipeUpForDrawer, swipeDownForNotifications) {
                            // Custom vertical-swipe detector (adapted from AOSP Launcher3's
                            // SingleAxisSwipeDetector.shouldScrollStart): a swipe counts as vertical
                            // only when |dy| clears the touch slop AND dominates |dx|. We consume the
                            // gesture only once that's decided, so the HorizontalPager still gets real
                            // sideways swipes but a flick up reliably "catches" and drives the drawer.
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val velocityTracker = VelocityTracker()
                                velocityTracker.addPosition(down.uptimeMillis, down.position)
                                var mode = 0 // 0 undecided, 1 drawer (up-drag)
                                var lastY = down.position.y
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        if (mode == 1) onDrawerSettle(velocityTracker.calculateVelocity().y)
                                        break
                                    }
                                    if (change.isConsumed && mode != 1) break // a child (icon) took over
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (mode == 0) {
                                        if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                                            if (dy < 0 && swipeUpForDrawer) {
                                                mode = 1
                                                change.consume()
                                                onDrawerDrag(dy) // jump to follow the finger (incl. slop)
                                                lastY = change.position.y
                                            } else if (dy > 0 && swipeDownForNotifications) {
                                                change.consume()
                                                onOpenNotifications()
                                                break // one-shot
                                            } else {
                                                break // wrong direction or disabled
                                            }
                                        } else if (abs(dx) > touchSlop && abs(dx) >= abs(dy)) {
                                            break // horizontal → leave it to the pager
                                        }
                                    } else if (mode == 1) {
                                        change.consume()
                                        onDrawerDrag(change.position.y - lastY)
                                        lastY = change.position.y
                                    }
                                }
                            }
                        }
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
                                    onOpenSettings()
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
                                        cellW, cellH, columns, rows, pageCount,
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
                                                // Static long-press → show the menu anchored under the
                                                // icon (after release, so it can't steal the drag).
                                                val anchor = dragController.gridBounds.topLeft + Offset(
                                                    d.cellX * cellW + cellW / 2f,
                                                    d.cellY * cellH + cellH,
                                                )
                                                onAppMenu(
                                                    d.app,
                                                    IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()),
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
