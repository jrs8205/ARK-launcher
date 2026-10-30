package org.arkikeskus.launcher.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.ui.AppActionPopup
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.AppShortcuts
import org.arkikeskus.launcher.ui.DockTarget
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.DismissWhenDragMoves
import org.arkikeskus.launcher.ui.FolderIconButton
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.dragPointer
import org.arkikeskus.launcher.ui.IconPress
import org.arkikeskus.launcher.ui.IconPressOutcome
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.PopupAction
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.arkikeskus.launcher.ui.component.LocalIconScale
import org.arkikeskus.launcher.ui.component.iconSizeForCell
import org.arkikeskus.launcher.ui.component.labelBlockHeight
import org.arkikeskus.launcher.ui.component.labelFontFactor
import kotlin.math.abs
import kotlin.math.roundToInt

private const val FOLDER_COLUMNS = 4

/**
 * The open folder: a card in the home screen's own window (not a sheet in a window of its own, so a
 * drag can continue onto the home surfaces) over a scrim that closes it when tapped. Inside: the
 * name, editable in place, and the apps in a four-column grid.
 *
 * One gesture detector on the grid's container owns every icon touch — the icons publish their
 * bounds and the container hit-tests the DOWN. A tap launches the app and closes the folder; a
 * still hold lifts the icon into the shared [HomeDragController] (haptic) and, released still, opens
 * the same menu as on the home screen. On a locked desktop a hold does nothing, as on every other
 * surface. The press phase follows the drawer's rules: a vertical pull before the hold completes is
 * the grid's scroll and is never consumed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCard(
    folder: PlacedFolder,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    locked: Boolean,
    dragController: HomeDragController,
    onRename: (String) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onRenameApp: (AppItem) -> Unit,
    onRemoveFromFolder: (AppItem) -> Unit,
    onPinShortcut: (AppShortcuts.Item) -> Unit,
    /** Release inside the grid at a new slot: [before] is the visible app that will follow, null = last. */
    onReorder: (app: AppItem, before: AppItem?) -> Unit,
    onDropOnHome: (app: AppItem, page: Int, cellX: Int, cellY: Int) -> Unit,
    /** Released over the dock where it can land (see [HomeDragController.dockAccepts]). */
    onDropOnDock: (AppItem, DockTarget) -> Unit,
    onDropOnRemove: (AppItem) -> Unit,
    /** The gesture that left the card has ended (whatever the drop was): the host closes the folder. */
    onDragOutEnd: () -> Unit,
    onDismiss: () -> Unit,
    /** The editor's "Delete folder", confirmed: the folder goes with every app in it. */
    onDelete: () -> Unit = {},
    /** The menu's Widgets row (an app with home-screen widgets); null hides it, e.g. a locked desktop. */
    onWidgets: ((AppItem) -> Unit)? = null,
    /** The icon picked for the folder beside its name (null = back to the preview of its apps). */
    onSetIcon: (CustomIcon?) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val iconScale = LocalIconScale.current
    val labelScale = LocalAppLabelScale.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height

    val currentOnAppClick by rememberUpdatedState(onAppClick)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnDropOnHome by rememberUpdatedState(onDropOnHome)
    val currentOnDropOnDock by rememberUpdatedState(onDropOnDock)
    val currentOnDropOnRemove by rememberUpdatedState(onDropOnRemove)
    val currentOnDragOutEnd by rememberUpdatedState(onDragOutEnd)
    val currentFolder by rememberUpdatedState(folder)

    // The card opens to look (issue #20); the pencil turns the header into the editor.
    var editing by remember(folder.id) { mutableStateOf(false) }
    var confirmDelete by remember(folder.id) { mutableStateOf(false) }

    // Root-space bounds of the card: a lifted icon pulled past them leaves the folder for the home
    // screen. The card then fades out but STAYS COMPOSED until the gesture ends — removing the node
    // that owns the pointer gesture would cancel the drag — and the host closes it on [onDragOutEnd].
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    var dragOut by remember { mutableStateOf(false) }
    val dragOutAlpha = remember { Animatable(1f) }
    LaunchedEffect(dragOut) {
        if (dragOut) dragOutAlpha.animateTo(0f, tween(durationMillis = 180))
    }

    // The long-pressed app and its icon's root bounds, for the menu's anchor.
    var menu by remember { mutableStateOf<Pair<AppItem, Rect>?>(null) }
    // An icon's menu opens as the icon lifts; pulling the icon on closes it (issue #12).
    DismissWhenDragMoves(dragController) { menu = null }
    // Live reorder: the slot under the finger while a lifted icon moves inside the card, and the
    // order a release committed, kept (optimistically) until the folder's apps change — to that
    // order once the database caught up, or to whatever else they became.
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    var pendingOrder by remember { mutableStateOf<List<AppItem>?>(null) }
    var pendingBaseline by remember { mutableStateOf<List<AppItem>?>(null) }
    LaunchedEffect(folder.apps) {
        if (pendingOrder != null && folder.apps != pendingBaseline) pendingOrder = null
    }
    val shownBase = pendingOrder ?: folder.apps
    val currentShownBase by rememberUpdatedState(shownBase)
    val dragged = dragController.draggedApp
    val reordering = dragController.moving && !dragController.leftSource &&
        dragController.source == DragSource.Folder && dragged != null
    val shown = targetIndex?.takeIf { reordering }?.let { reorderedFolderApps(shownBase, dragged!!, it) } ?: shownBase
    // The grid container's coordinates. The icons' geometry (the DOWN hit-test, the reorder slot, the
    // menu's anchor) comes from the grid's own layout info — one source, always current. A map of
    // bounds the icons published kept the rectangles of icons that had left the folder or scrolled
    // out, and a DOWN over the icon that took the slot could match the stale one first.
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var gridWidthPx by remember { mutableIntStateOf(0) }
    // An icon is lifted: the grid stops scrolling until the gesture ends.
    var lifted by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("folderScrim")
            .graphicsLayer { alpha = dragOutAlpha.value }
            .background(Color.Black.copy(alpha = 0.5f))
            // The scrim swallows every touch under it: nothing reaches the home grid behind. For
            // accessibility services it is a pane named after the folder (announced as it opens,
            // dismissable): a node that matters covers the home nodes under it, so a swipe through
            // the screen cannot reach a home icon, a widget or the dock while the folder is open.
            .semantics {
                paneTitle = folder.name
                dismiss { currentOnDismiss(); true }
            }
            .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .testTag("folderCard")
                // A tap in the card's own padding closes nothing (and the consumed release keeps
                // the scrim's tap from firing for a press that began inside the card).
                .pointerInput(Unit) { detectTapGestures { } }
                .onGloballyPositioned { cardBounds = it.boundsInRoot() },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                var name by remember(folder.id) { mutableStateOf(folder.name) }
                // The home tile's see-through backing vanishes on the card: give the preview one.
                val preview: @Composable (CustomIcon?, Dp) -> Unit = { icon, size ->
                    Box(
                        Modifier.background(
                            if (icon == null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            RoundedCornerShape(15.dp),
                        ),
                    ) {
                        FolderIcon(
                            name = folder.name, apps = folder.apps, showLabel = false, badgeCount = 0,
                            badgeShowCount = false, size = size, customIcon = icon,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (editing) {
                        // The folder's icon: a tap picks another (Nova and Lawnchair let folders have one).
                        FolderIconButton(icon = folder.icon, preview = preview, onPick = onSetIcon)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; onRename(it) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.folder_name_label)) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { editing = false }) {
                            Icon(painterResource(LauncherIcons.Check), contentDescription = stringResource(R.string.folder_done))
                        }
                    } else {
                        // A look at the folder (issue #20): the name as text beside its preview, which the
                        // text speaks for; the pencil opens the editor. A locked desktop edits nothing.
                        Box(Modifier.padding(4.dp).clearAndSetSemantics { }) { preview(folder.icon, 48.dp) }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!locked) {
                            IconButton(onClick = { editing = true }) {
                                Icon(painterResource(LauncherIcons.Edit), contentDescription = stringResource(R.string.folder_edit))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // The icon follows the user's icon-size setting, capped by the slot like the drawer.
                val nominalIconSize = 56.dp * iconScale
                val iconSize = if (gridWidthPx > 0) {
                    iconSizeForCell(with(density) { (gridWidthPx.toFloat() / FOLDER_COLUMNS).toDp() }, nominalIconSize)
                } else {
                    nominalIconSize
                }
                val rowHeight = iconSize +
                    labelBlockHeight(showLabel = true, labelScale = labelScale, fontFactor = labelFontFactor(density.fontScale), lines = 2) +
                    20.dp
                // Up to four rows without scrolling when the screen allows; taller folders scroll.
                val gridMaxHeight: Dp = min(rowHeight * 4, with(density) { windowHeightPx.toDp() } * 0.6f)
                val gridState = rememberLazyGridState()
                // A visible icon's root bounds, for the menu's anchor; null once it has scrolled out.
                fun itemRootBounds(key: String): Rect? {
                    val coords = gridCoords ?: return null
                    return gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.rootBounds(coords)
                }
                // The preview reorders the items by key, and the grid anchors its scroll position to
                // the first visible item's KEY: the lifted icon leaving the top row for a lower one
                // would scroll the grid a row under a finger that has not moved — and the next read
                // would put the icon a row lower again. Before every change to the shown order the
                // position is kept by INDEX instead (applied at the same remeasure as the new order).
                fun keepScrollPositionByIndex() =
                    gridState.requestScrollToItem(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("folderGrid")
                        .onGloballyPositioned { gridCoords = it; gridWidthPx = it.size.width }
                        .pointerInput(locked) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val coords = gridCoords ?: return@awaitEachGesture
                                // The grid fills this container from its origin: its items' offsets
                                // are in these coordinates.
                                val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.bounds.contains(down.position.round()) }
                                    ?: return@awaitEachGesture
                                val app = currentShownBase.firstOrNull { it.key == hit.key } ?: return@awaitEachGesture
                                val itemRect = hit.rootBounds(coords)
                                val outcome = awaitFolderPress(down, itemRect) { coords.localToRoot(it) }
                                val holdPos = when (outcome) {
                                    IconPressOutcome.Tap -> {
                                        currentOnAppClick(app)
                                        currentOnDismiss()
                                        return@awaitEachGesture
                                    }
                                    IconPressOutcome.Cancelled -> return@awaitEachGesture
                                    is IconPressOutcome.Held -> outcome.position
                                }
                                // Desktop locked: a hold does nothing (no lift, no menu); a tap still launches.
                                if (locked) return@awaitEachGesture
                                val token = Any()
                                if (!dragController.start(app, DragSource.Folder, coords.localToRoot(holdPos), token)) {
                                    return@awaitEachGesture
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                lifted = true
                                // The menu opens now, under the still-held finger (issue #12); pulling
                                // the icon on closes it (DismissWhenDragMoves below) and the drag goes on.
                                menu = app to itemRect
                                val dragStartPx = IconPress.DragStartThreshold.toPx()
                                try {
                                    val completed = dragPointer(down.id) { change ->
                                        change.consume()
                                        if (!dragController.owns(token)) return@dragPointer
                                        // A still long-press (with finger jitter) keeps showing the
                                        // menu on release; only a pull past the threshold moves.
                                        if (!dragController.moving &&
                                            IconPress.startsDrag(change.position, holdPos, dragStartPx)
                                        ) {
                                            // Moving inside the card is a reorder, not a drag over
                                            // the home screen: nothing lights up behind the card.
                                            dragController.beginMove(leftSource = false)
                                        }
                                        if (!dragController.moving) return@dragPointer
                                        val root = coords.localToRoot(change.position)
                                        dragController.update(root)
                                        // Past the card's edge the drag is over the home screen:
                                        // the home cues may show, the card fades, and coming back
                                        // over it does not reopen the folder.
                                        if (!dragController.leftSource && !cardBounds.contains(root)) {
                                            dragController.markLeftSource()
                                            dragOut = true
                                            keepScrollPositionByIndex()
                                            targetIndex = null
                                        }
                                        if (!dragController.leftSource) {
                                            val index = folderSlotIndex(
                                                change.position, size.width.toFloat(),
                                                rowBands(gridState.layoutInfo.visibleItemsInfo), currentShownBase.size,
                                            )
                                            if (index != targetIndex) {
                                                keepScrollPositionByIndex()
                                                targetIndex = index
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                    // Nothing on release once HOME cancelled the lift.
                                    val live = completed && dragController.owns(token)
                                    when {
                                        !live -> Unit
                                        // A still release leaves the menu, opened at the lift, as it is.
                                        !dragController.moving -> Unit
                                        dragController.leftSource -> {
                                            // The drawer's drop rules: the remove pill, the dock when
                                            // it has room, a home cell; anywhere else leaves the app
                                            // in the folder (which the host closes all the same).
                                            val root = dragController.rootPosition
                                            when {
                                                dragController.isOverRemove(root) -> currentOnDropOnRemove(app)
                                                dragController.isOverDock(root) && dragController.dockAccepts(root) ->
                                                    currentOnDropOnDock(app, dragController.dockTargetAt(root))
                                                dragController.isOverGrid(root) -> {
                                                    val (page, cx, cy) = dragController.cellAt(root)
                                                    currentOnDropOnHome(app, page, cx, cy)
                                                }
                                            }
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        else -> {
                                            val index = targetIndex
                                            val base = currentShownBase
                                            val preview = if (index != null) reorderedFolderApps(base, app, index) else base
                                            if (preview != base) {
                                                pendingOrder = preview
                                                pendingBaseline = currentFolder.apps
                                                currentOnReorder(app, preview.getOrNull(preview.indexOf(app) + 1))
                                            }
                                        }
                                    }
                                } finally {
                                    // The shown order may revert here (a cancelled lift): same rule.
                                    keepScrollPositionByIndex()
                                    dragController.stop(token)
                                    lifted = false
                                    targetIndex = null
                                    if (dragOut) currentOnDragOutEnd()
                                }
                            }
                        },
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(FOLDER_COLUMNS),
                        state = gridState,
                        userScrollEnabled = !lifted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = gridMaxHeight),
                    ) {
                        items(items = shown, key = { it.key }) { app ->
                            AppIcon(
                                appItem = app,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                iconSize = iconSize,
                                showLabel = true,
                                maxLabelLines = 2,
                                // Every slot reserves both label lines, as the home grid's do: the
                                // rows stay one height (the one [gridMaxHeight] counts) whether or
                                // not a name wraps, and the icons line up across rows.
                                reserveLabelLines = true,
                                badgeCount = badges[app.badgeKey] ?: 0,
                                badgeShowCount = badgeShowCount,
                                badgeScale = badgeScale,
                                modifier = Modifier
                                    // The pointer gesture lives on the container; these keep the
                                    // accessibility actions a clickable icon would have had.
                                    .semantics(mergeDescendants = true) {
                                        onClick { currentOnAppClick(app); currentOnDismiss(); true }
                                        if (!locked) onLongClick { menu = app to (itemRootBounds(app.key) ?: Rect.Zero); true }
                                    }
                                    .animateItem()
                                    // The moving icon's slot stays reserved and shows a faint copy —
                                    // the hole the others slide around; the shell draws the real one.
                                    .graphicsLayer {
                                        alpha = if (dragController.moving && dragController.source == DragSource.Folder &&
                                            dragController.draggedApp?.key == app.key
                                        ) 0.3f else 1f
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
                if (editing) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.folder_delete))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.folder_delete_title)) },
            text = { Text(stringResource(R.string.folder_delete_home_text, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.folder_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    menu?.let { (app, bounds) ->
        // Anchored like the drawer's menu: below an icon in the upper half of the window, above one
        // lower down, at the icon's edge so the card clears it with a small gap.
        val preferAbove = bounds.center.y > windowHeightPx / 2
        val anchorY = (if (preferAbove) bounds.top else bounds.bottom).roundToInt()
        AppActionPopup(
            app = app,
            anchor = IntOffset(bounds.center.x.roundToInt(), anchorY),
            preferAbove = preferAbove,
            actions = listOf(
                PopupAction(stringResource(R.string.app_info), LauncherIcons.Info) { AppActions.openAppInfo(context, app) },
                PopupAction(stringResource(R.string.rename), LauncherIcons.Edit) { onRenameApp(app) },
                PopupAction(stringResource(R.string.folder_remove_app), LauncherIcons.Close) { onRemoveFromFolder(app) },
                PopupAction(stringResource(R.string.uninstall), LauncherIcons.Delete) { AppActions.uninstall(context, app) },
            ),
            onDismiss = { menu = null },
            onPinShortcut = onPinShortcut,
            onWidgets = onWidgets?.let { { it(app) } },
            // Opened at the lift, under the still-held finger (issue #12).
            held = dragController.isDragging,
        )
    }
}

/** [apps] with [moving] taken out and put back at [toIndex] (clamped to the list). */
internal fun reorderedFolderApps(apps: List<AppItem>, moving: AppItem, toIndex: Int): List<AppItem> {
    val rest = apps.filter { it.key != moving.key }
    if (rest.size == apps.size) return apps
    return rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), moving) }
}

/** A visible item's slot in the grid's coordinates (the icon with its padding). */
private val LazyGridItemInfo.bounds: IntRect get() = IntRect(offset, size)

/** [bounds] in root coordinates, through the grid container's [coords]. */
private fun LazyGridItemInfo.rootBounds(coords: LayoutCoordinates): Rect =
    Rect(coords.localToRoot(offset.toOffset()), size.toSize())

/** A visible row of the grid: its index and where it begins (y in the grid's coordinates). */
internal data class RowBand(val row: Int, val top: Int)

/** The rows the grid shows (from its visibleItemsInfo), top to bottom, where each really begins —
 *  rows are not all one height when a name wraps, and the first visible row may begin above the top. */
internal fun rowBands(items: List<LazyGridItemInfo>): List<RowBand> =
    items.groupBy { it.row }.map { (row, inRow) -> RowBand(row, inRow.minOf { it.offset.y }) }.sortedBy { it.row }

/**
 * The grid slot under a finger at [local] (the grid container's coordinates): the column from x, the
 * row the last of the visible [rows] that begins above the finger — so a finger below the last row
 * targets that row, one above the first the first; clamped to the [count] apps the folder shows.
 */
internal fun folderSlotIndex(
    local: Offset,
    gridWidthPx: Float,
    rows: List<RowBand>,
    count: Int,
    columns: Int = FOLDER_COLUMNS,
): Int {
    if (rows.isEmpty()) return 0
    val slotWidth = (gridWidthPx / columns).coerceAtLeast(1f)
    val col = (local.x / slotWidth).toInt().coerceIn(0, columns - 1)
    val row = (rows.lastOrNull { it.top <= local.y } ?: rows.first()).row
    return (row * columns + col).coerceIn(0, (count - 1).coerceAtLeast(0))
}

/**
 * The press phase of a folder icon after its [down], run by the grid's container: the drawer's
 * rules. The moves are never consumed, so a vertical pull past the slop before the hold completes
 * is the grid's scroll (the grid consumes it and the press ends as [IconPressOutcome.Cancelled]);
 * a sideways roll only ends the hold once the finger leaves the icon ([itemRect], root space, plus
 * the slop). A clean release is a [IconPressOutcome.Tap]; the timeout a [IconPressOutcome.Held].
 */
private suspend fun AwaitPointerEventScope.awaitFolderPress(
    down: PointerInputChange,
    itemRect: Rect,
    toRoot: (Offset) -> Offset,
): IconPressOutcome {
    val slop = viewConfiguration.touchSlop
    val itemSize = IntSize(itemRect.width.roundToInt(), itemRect.height.roundToInt())
    var holdPos = down.position
    val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var ended: IconPressOutcome? = null
        while (ended == null) {
            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            ended = when {
                c == null || c.isConsumed ||
                    abs(c.position.y - down.position.y) > slop ||
                    !IconPress.staysOnIcon(toRoot(c.position) - itemRect.topLeft, itemSize, slop) ->
                    IconPressOutcome.Cancelled
                !c.pressed -> {
                    c.consume()
                    IconPressOutcome.Tap
                }
                else -> {
                    holdPos = c.position
                    null
                }
            }
        }
        ended
    }
    return ended ?: IconPressOutcome.Held(holdPos)
}
