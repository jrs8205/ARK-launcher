package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DockTarget
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.IconPress
import org.arkikeskus.launcher.ui.IconPressOutcome
import org.arkikeskus.launcher.ui.appLaunchTouch
import org.arkikeskus.launcher.ui.awaitIconPress
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.LocalAppLabelLines
import org.arkikeskus.launcher.ui.component.LocalIconScale
import org.arkikeskus.launcher.ui.component.iconSizeForCell
import org.arkikeskus.launcher.ui.dragPointer
import org.arkikeskus.launcher.ui.rememberAppLaunchOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Bottom dock: a translucent rounded bar of slots holding app icons and folders (Launcher3's
 * hotseat). Tap an app to launch it, a folder to open its card; long-press and drag sideways to
 * reorder; long-press and drag **up onto the home screen** to move the item out of the dock (routed
 * via [dragController] / [onMoveToHome] / [onMoveFolderToHome]). While an item is dragged its in-dock
 * copy is hidden and a floating copy follows the finger across surfaces (an app's drawn by the shell,
 * a folder's by the home screen).
 *
 * Drops INTO the dock are decided by the dropping surface from the slots this bar publishes
 * ([HomeDragController.dockSlots] → [HomeDragController.dockTargetAt]): between tiles while the dock
 * has room, or onto a tile — an app there folds with the dropped one, a folder takes it in — even
 * when the dock is full. The bar lights up while a drop can land, and the tile under the finger too.
 */
@Composable
fun Dock(
    entries: List<DockEntry>,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    labelColor: Color = Color.White,
    backgroundAlpha: Float,
    locked: Boolean,
    dragController: HomeDragController,
    onAppClick: (AppItem) -> Unit,
    onReorder: (List<DockEntry>) -> Unit,
    onMoveToHome: (AppItem, Int, Int, Int) -> Unit,
    onRemoveFromDock: (AppItem) -> Unit = {},
    modifier: Modifier = Modifier,
    onAppMenu: (AppItem, IntOffset) -> Unit = { _, _ -> },
    /** The name under a folder's tile, as on home (issue #17); [showLabels] off hides it too. */
    showFolderNames: Boolean = true,
    onOpenFolder: (PlacedFolder) -> Unit = {},
    /** A dock folder dropped on a home cell; the host moves it only while the cell is free. */
    onMoveFolderToHome: (folderId: Long, page: Int, cellX: Int, cellY: Int) -> Unit = { _, _, _, _ -> },
    /** A dock folder that shows no apps dropped on the remove pill. */
    onRemoveFolder: (folderId: Long) -> Unit = {},
    /** A dock app dropped onto ANOTHER dock tile's middle: folds with that app or joins that folder. */
    onDropOnTile: (AppItem, DockTarget.Onto) -> Unit = { _, _ -> },
) {
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    // The lift [draggingIndex]/[dragOffsetX] belong to. After HOME cancels a lift, a still-held old
    // finger must neither move nor reset a newer lift's dock state.
    var liftToken by remember { mutableStateOf<Any?>(null) }
    // Each slot's top-left and bounds in root coords: a drag is reported to the controller in root
    // space, and the bounds are published for the drop-target decision of the other surfaces.
    val itemRoots = remember { mutableStateMapOf<Int, Offset>() }
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // The per-slot pointerInput block outlives recomposition (its keys don't include the order), so a
    // reorder must build on the *latest* list: a slot that stayed put kept the list captured before
    // its neighbours moved, and dragging it afterwards silently undid that earlier reorder.
    val latestEntries by rememberUpdatedState(entries)
    // The dock leaves composition when it is switched off in settings; without this its last bounds
    // stayed in the controller, so the workspace cells that expanded into that area still hit-tested
    // as "over the dock" and rejected every drop (and swallowed the gestures that started there).
    DisposableEffect(dragController) {
        onDispose {
            dragController.dockBounds = Rect.Zero
            dragController.dockSlots = emptyList()
        }
    }
    // Icon size derived from the slot so 6–7 dock icons fit a narrow screen (fixed 52dp overlapped).
    // The user's icon-size setting scales the nominal size before the slot clamp.
    val nominalIconSize = DOCK_ICON_SIZE * LocalIconScale.current
    val dockIconSize = if (rowWidthPx > 0 && entries.isNotEmpty()) {
        iconSizeForCell(with(density) { (rowWidthPx.toFloat() / entries.size).toDp() }, nominalIconSize)
    } else {
        nominalIconSize
    }

    val shape = RoundedCornerShape(30.dp)
    // Live drop feedback: the bar lights up while an item from another surface hovers it and has
    // somewhere to land (Launcher3's onDragEnter/acceptDrop); the tile a drop would fold into or
    // join lights up on its own. derivedStateOf so we recompose only when these change, not per frame.
    val incoming by remember {
        derivedStateOf {
            dragController.isIncomingFrom(DragSource.Home, DragSource.Drawer, DragSource.Folder) ||
                homeFolderIncoming(dragController)
        }
    }
    val highlighted by remember {
        derivedStateOf { incoming && dragController.dockAccepts(dragController.rootPosition) }
    }
    // The tile an app from another surface would fold into or join: lit at once (that drop has no
    // other meaning). One of the dock's own apps over another tile could as well be on its way past
    // it to a new slot, so it folds only after resting there (Launcher3's folder-creation hover).
    val incomingOnto by remember {
        derivedStateOf {
            if (!incoming || dragController.draggedApp == null) return@derivedStateOf -1
            val p = dragController.rootPosition
            if (dragController.isOverDock(p)) (dragController.dockTargetAt(p) as? DockTarget.Onto)?.index ?: -1 else -1
        }
    }
    val ownOnto by remember {
        derivedStateOf {
            val ownDrag = dragController.source == DragSource.Dock && dragController.moving && draggingIndex >= 0
            if (!ownDrag || dragController.draggedApp == null) return@derivedStateOf -1
            val p = dragController.rootPosition
            if (!dragController.isOverDock(p)) return@derivedStateOf -1
            val onto = (dragController.dockTargetAt(p) as? DockTarget.Onto)?.index ?: -1
            if (onto == draggingIndex) -1 else onto
        }
    }
    var armedOwnOnto by remember { mutableIntStateOf(-1) }
    LaunchedEffect(ownOnto) {
        armedOwnOnto = -1
        if (ownOnto >= 0) {
            delay(DOCK_FOLD_HOVER_MS)
            armedOwnOnto = ownOnto
        }
    }
    val hoveredSlot = remember { derivedStateOf { if (incomingOnto >= 0) incomingOnto else armedOwnOnto } }

    Surface(
        modifier = modifier
            .onGloballyPositioned { dragController.dockBounds = it.boundsInRoot() }
            .border(2.dp, Color.White.copy(alpha = if (highlighted) 0.8f else 0f), shape),
        color = Color.Black.copy(alpha = backgroundAlpha),
        shape = shape,
    ) {
        Row(
            // Keep a full-height bar even when empty (no entries yet) so apps can be dragged in.
            modifier = Modifier
                .heightIn(min = DOCK_ROW_MIN_HEIGHT)
                .padding(horizontal = 8.dp, vertical = DOCK_ROW_VERTICAL_PADDING)
                .onSizeChanged { rowWidthPx = it.width },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEachIndexed { index, entry ->
                val isDragging = index == draggingIndex
                val slot = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        itemRoots[index] = coords.positionInRoot()
                        slotBounds[index] = coords.boundsInRoot()
                        // Publish the shown slots in order, once each has reported; a list of equal
                        // rectangles is no update, so this never recomposes anything by itself.
                        val slots = (0 until latestEntries.size).map { slotBounds[it] ?: return@onGloballyPositioned }
                        dragController.dockSlots = slots
                    }
                    // Hide the in-dock copy only once it's moving; the floating copy is drawn above.
                    // While merely lifted (menu showing) it stays visible.
                    .graphicsLayer { alpha = if (isDragging && dragController.moving) 0f else 1f }
                    .tileHighlight(hoveredSlot, index, shape = RoundedCornerShape(16.dp))
                when (entry) {
                    is DockApp -> DockAppSlot(
                        app = entry.app, index = index, slot = slot, dockIconSize = dockIconSize,
                        badges = badges, badgeShowCount = badgeShowCount, badgeScale = badgeScale,
                        showLabels = showLabels, labelColor = labelColor, locked = locked,
                        dragController = dragController, haptics = haptics,
                        rowWidthPx = { rowWidthPx }, itemRoots = itemRoots, latestEntries = { latestEntries },
                        onAppClick = onAppClick, onAppMenu = onAppMenu,
                        onLift = { token -> liftToken = token; draggingIndex = index; dragOffsetX = 0f },
                        onDragX = { dx -> dragOffsetX += dx },
                        onDropped = { rootPos, app, current ->
                            val onto = if (dragController.isOverDock(rootPos)) dragController.dockTargetAt(rootPos) as? DockTarget.Onto else null
                            when {
                                dragController.isOverRemove(rootPos) -> onRemoveFromDock(app)
                                dragController.isOverGrid(rootPos) -> {
                                    val (page, cx, cy) = dragController.cellAt(rootPos)
                                    onMoveToHome(app, page, cx, cy)
                                }
                                // Rested on another tile's middle: a folder of the two, or into that folder.
                                onto != null && onto.index == armedOwnOnto && onto.index < current.size -> onDropOnTile(app, onto)
                                // By the app's key, not the row id this composition saw: a restored backup
                                // keeps the apps under new row ids while the slot's detector lives on.
                                else -> {
                                    val rowId = current.firstOrNull { it is DockApp && it.app.key == app.key }?.rowId ?: entry.rowId
                                    reorderedDock(current, rowId, dragOffsetX, rowWidthPx)?.let(onReorder)
                                }
                            }
                        },
                        onReleased = { token ->
                            if (liftToken === token) {
                                draggingIndex = -1
                                dragOffsetX = 0f
                                liftToken = null
                            }
                        },
                    )
                    is DockFolder -> DockFolderSlot(
                        folder = entry.folder, index = index, slot = slot, dockIconSize = dockIconSize,
                        badges = badges, badgeShowCount = badgeShowCount, badgeScale = badgeScale,
                        showLabels = showLabels, showFolderNames = showFolderNames, labelColor = labelColor, locked = locked,
                        dragController = dragController, haptics = haptics,
                        itemRoots = itemRoots,
                        onOpenFolder = onOpenFolder,
                        onLift = { token -> liftToken = token; draggingIndex = index; dragOffsetX = 0f },
                        onDragX = { dx -> dragOffsetX += dx },
                        onDropped = { rootPos, removable ->
                            when {
                                removable && dragController.isOverRemove(rootPos) -> onRemoveFolder(entry.folder.id)
                                dragController.isOverGrid(rootPos) -> {
                                    val (page, cx, cy) = dragController.cellAt(rootPos)
                                    onMoveFolderToHome(entry.folder.id, page, cx, cy)
                                }
                                else -> reorderedDock(latestEntries, entry.rowId, dragOffsetX, rowWidthPx)?.let(onReorder)
                            }
                        },
                        onReleased = { token ->
                            if (liftToken === token) {
                                draggingIndex = -1
                                dragOffsetX = 0f
                                liftToken = null
                            }
                        },
                    )
                }
            }
        }
    }
}

/** A home folder lifted on the grid and pulled over the dock (its drag stays local to the grid, so
 *  it is never "incoming" in the controller's sense; the dock reads the lift directly). */
private fun homeFolderIncoming(c: HomeDragController): Boolean =
    c.draggedFolderId != null && c.source == DragSource.Home && c.moving

/** The dock's order after the slot of [rowId] was pulled [dragOffsetX] px sideways; null = unchanged. */
internal fun reorderedDock(current: List<DockEntry>, rowId: Long, dragOffsetX: Float, rowWidthPx: Int): List<DockEntry>? {
    val from = current.indexOfFirst { it.rowId == rowId }
    if (from < 0) return null
    val slot = if (current.isNotEmpty()) rowWidthPx.toFloat() / current.size else 1f
    val shift = if (slot > 0f) (dragOffsetX / slot).roundToInt() else 0
    val target = (from + shift).coerceIn(0, (current.size - 1).coerceAtLeast(0))
    if (target == from) return null
    return current.toMutableList().also { it.add(target, it.removeAt(from)) }
}

/** A ring on the tile at [index] while a drop would fold into it or join it. */
private fun Modifier.tileHighlight(hovered: State<Int>, index: Int, shape: RoundedCornerShape): Modifier =
    this.border(2.dp, Color.White.copy(alpha = if (hovered.value == index) 0.8f else 0f), shape)

@Composable
private fun RowScope.DockAppSlot(
    app: AppItem,
    index: Int,
    slot: Modifier,
    dockIconSize: Dp,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    labelColor: Color,
    locked: Boolean,
    dragController: HomeDragController,
    haptics: HapticFeedback,
    rowWidthPx: () -> Int,
    itemRoots: Map<Int, Offset>,
    latestEntries: () -> List<DockEntry>,
    onAppClick: (AppItem) -> Unit,
    onAppMenu: (AppItem, IntOffset) -> Unit,
    onLift: (token: Any) -> Unit,
    onDragX: (Float) -> Unit,
    onDropped: (rootPos: Offset, app: AppItem, current: List<DockEntry>) -> Unit,
    onReleased: (token: Any) -> Unit,
) {
    // The whole slot takes the tap; the launch zooms from the icon in it.
    val launchOrigin = rememberAppLaunchOrigin()
    // The gesture below outlives a label or icon change of the app (its keys do not change) and
    // must act on the item as it is NOW, not as it was at the first touch, and hand its drop to the
    // host's CURRENT lambda, not the one this composition captured.
    val currentApp by rememberUpdatedState(app)
    val currentOnDropped by rememberUpdatedState(onDropped)
    val slotCount = latestEntries().size
    Box(
        modifier = slot
            .appLaunchTouch(app, launchOrigin)
            // One unified gesture (like Workspace): quick tap launches; a still long-press lifts →
            // drag (reorder / drop onto home) or, with no movement, opens the menu. A single
            // detector avoids the tap-vs-long-press conflict that fired both.
            .pointerInput(app.key, slotCount, rowWidthPx(), locked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Claim the gesture immediately (same as Workspace icons): a dock touch belongs
                    // to the icon, so nothing else can steal it — not the pager, not finger jitter
                    // during the long-press hold. Without this the long-press was flaky ("had to try
                    // several times to get the popup"): the tiniest drift leaked out and cancelled
                    // the hold before it could open the menu.
                    down.consume()
                    val slop = viewConfiguration.touchSlop
                    var tapped = false
                    var abandoned = false
                    // The lift starts where the finger is when the hold completes.
                    var holdPos = down.position
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            if (c == null) {
                                abandoned = true
                                return@withTimeoutOrNull
                            }
                            c.consume()
                            if (!c.pressed) {
                                tapped = true
                                return@withTimeoutOrNull
                            }
                            if (!IconPress.staysOnIcon(c.position, size, slop)) {
                                abandoned = true
                                return@withTimeoutOrNull
                            }
                            holdPos = c.position
                        }
                    }
                    if (tapped) {
                        onAppClick(currentApp)
                        return@awaitEachGesture
                    }
                    if (abandoned) return@awaitEachGesture
                    // Desktop locked: long-press does nothing (no menu, no lift); tap still launches.
                    if (locked) return@awaitEachGesture
                    // LONG PRESS → lift (refused while another finger holds one)
                    val token = Any()
                    val itemRoot = itemRoots[index] ?: Offset.Zero
                    if (!dragController.start(currentApp, DragSource.Dock, itemRoot + holdPos, token)) {
                        return@awaitEachGesture
                    }
                    onLift(token)
                    val dragStartPx = IconPress.DragStartThreshold.toPx()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // The menu opens now, above the item, under the still-held finger (issue #12);
                    // pulling the icon on closes it and the drag goes on.
                    run {
                        val slotW = if (slotCount > 0) rowWidthPx().toFloat() / slotCount else 0f
                        val anchor = Offset(itemRoot.x + slotW / 2f, itemRoot.y)
                        onAppMenu(currentApp, IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()))
                    }
                    try {
                        val completed = dragPointer(down.id) { change ->
                            // Accumulate the delta BEFORE consuming: positionChange() returns
                            // Offset.Zero once the change is consumed, which silently zeroed the
                            // offset and broke in-dock reordering (the drag-out path still worked
                            // because it uses the absolute change.position below).
                            val dx = change.positionChange().x
                            change.consume()
                            if (!dragController.owns(token)) return@dragPointer
                            onDragX(dx)
                            // Only promote to a real move once the finger is pulled past the
                            // drag-start threshold FROM WHERE IT WAS PICKED UP. A drift during a
                            // static long-press must stay a long-press so the popup (app shortcuts)
                            // opens instead of a no-op reorder + 2nd haptic. Summing the travelled
                            // distance let plain finger jitter cross it.
                            if (!dragController.moving && IconPress.startsDrag(change.position, holdPos, dragStartPx)) {
                                dragController.beginMove()
                            }
                            if (dragController.moving) {
                                dragController.update((itemRoots[index] ?: itemRoot) + change.position)
                            }
                        }
                        // Nothing on release once HOME cancelled the lift.
                        val live = completed && dragController.owns(token)
                        if (live && dragController.moving) {
                            currentOnDropped(dragController.rootPosition, currentApp, latestEntries())
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        // A still release leaves the menu, opened at the lift, as it is.
                    } finally {
                        // Reset even on cancellation (node disposed / pointerInput restarted),
                        // mirroring the local-drag path in Workspace, so a dead gesture can't
                        // leave the shared controller lifted.
                        dragController.stop(token)
                        onReleased(token)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            appItem = app,
            labelColor = labelColor,
            showLabel = showLabels,
            iconSize = dockIconSize,
            launchOrigin = launchOrigin,
            // The dock stays a compact bar: one label line even when home labels wrap.
            maxLabelLines = 1,
            badgeCount = badges[app.badgeKey] ?: 0,
            badgeShowCount = badgeShowCount,
            badgeScale = badgeScale,
        )
    }
}

@Composable
private fun RowScope.DockFolderSlot(
    folder: PlacedFolder,
    index: Int,
    slot: Modifier,
    dockIconSize: Dp,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    showFolderNames: Boolean,
    labelColor: Color,
    locked: Boolean,
    dragController: HomeDragController,
    haptics: HapticFeedback,
    itemRoots: Map<Int, Offset>,
    onOpenFolder: (PlacedFolder) -> Unit,
    onLift: (token: Any) -> Unit,
    onDragX: (Float) -> Unit,
    onDropped: (rootPos: Offset, removable: Boolean) -> Unit,
    onReleased: (token: Any) -> Unit,
) {
    // Read when a gesture fires, not from the closure the detector was made with: the folder's apps
    // (and so its removability) change while the detector lives on.
    val currentFolder by rememberUpdatedState(folder)
    val currentOnOpen by rememberUpdatedState(onOpenFolder)
    val currentOnDropped by rememberUpdatedState(onDropped)
    Box(
        modifier = slot
            // One node for accessibility services, named by the icon's description, whose click
            // opens the folder: the pointer gesture below never reaches them.
            .semantics(mergeDescendants = true) { onClick { currentOnOpen(currentFolder); true } }
            // The home grid's folder gesture: tap / still hold opens, a hold then a pull lifts the
            // folder into the shared controller for a move (reorder here, a free home cell, the
            // remove pill for a folder that shows no apps). The press phase follows the icons' rule.
            .pointerInput(folder.id, locked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val press = awaitIconPress(down)
                    if (press is IconPressOutcome.Tap) { currentOnOpen(currentFolder); return@awaitEachGesture }
                    if (press !is IconPressOutcome.Held) return@awaitEachGesture
                    val holdPos = press.position
                    // Desktop locked: a hold does nothing (no move); a tap still opens.
                    if (locked) return@awaitEachGesture
                    val token = Any()
                    val itemRoot = itemRoots[index] ?: Offset.Zero
                    if (!dragController.startFolder(currentFolder.id, DragSource.Dock, itemRoot + holdPos, token)) {
                        return@awaitEachGesture
                    }
                    // Only a folder that shows no apps (all disabled or gone) can go to Remove.
                    val removable = currentFolder.apps.isEmpty()
                    onLift(token)
                    dragController.localGestureActive = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val dragStartPx = IconPress.DragStartThreshold.toPx()
                    try {
                        val completed = dragPointer(down.id) { change ->
                            val dx = change.positionChange().x
                            change.consume()
                            if (!dragController.owns(token)) return@dragPointer
                            onDragX(dx)
                            if (!dragController.moving && IconPress.startsDrag(change.position, holdPos, dragStartPx)) {
                                dragController.beginMove()
                                if (removable) dragController.localDragging = true
                            }
                            if (dragController.moving) {
                                dragController.update((itemRoots[index] ?: itemRoot) + change.position)
                            }
                        }
                        val live = completed && dragController.owns(token)
                        if (live && dragController.moving) {
                            currentOnDropped(dragController.rootPosition, removable)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (live) {
                            currentOnOpen(currentFolder)
                        }
                    } finally {
                        if (dragController.owns(token)) {
                            dragController.localDragging = false
                            dragController.localGestureActive = false
                        }
                        dragController.stop(token)
                        onReleased(token)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The dock stays a compact bar: one label line even when home labels wrap.
        CompositionLocalProvider(LocalAppLabelLines provides 1) {
            FolderIcon(
                name = folder.name,
                apps = folder.apps,
                showLabel = showLabels,
                showName = showFolderNames,
                badgeCount = folder.apps.sumOf { badges[it.badgeKey] ?: 0 },
                badgeShowCount = badgeShowCount,
                badgeScale = badgeScale,
                labelColor = labelColor,
                size = dockIconSize,
                customIcon = folder.icon,
            )
        }
    }
}

/** How long one of the dock's own apps rests on another tile before a release there folds the two
 *  (or joins the folder) instead of reordering — Launcher3's folder-creation hover. */
internal const val DOCK_FOLD_HOVER_MS = 250L

/** The dock's icon size, shrunk only when its entries don't fit the row at it. */
internal val DOCK_ICON_SIZE = 52.dp

/** The dock row's least height (it stays a full bar while empty) and its space above and below the icons. */
internal val DOCK_ROW_MIN_HEIGHT = 72.dp
internal val DOCK_ROW_VERTICAL_PADDING = 10.dp
