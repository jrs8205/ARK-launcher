package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.dragPointer
import org.arkikeskus.launcher.ui.IconPress
import org.arkikeskus.launcher.ui.appLaunchTouch
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.LocalIconScale
import org.arkikeskus.launcher.ui.component.iconSizeForCell
import org.arkikeskus.launcher.ui.rememberAppLaunchOrigin
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Bottom dock: a translucent rounded bar of favorite app icons. Tap to launch; long-press and drag
 * sideways to reorder; long-press and drag **up onto the home screen** to move the icon out of the
 * dock (routed via [dragController] / [onMoveToHome]). While an icon is dragged its in-dock copy is
 * hidden and the floating copy (drawn by HomeScreen) follows the finger across surfaces.
 */
@Composable
fun Dock(
    apps: List<AppItem>,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    labelColor: Color = Color.White,
    backgroundAlpha: Float,
    locked: Boolean,
    dragController: HomeDragController,
    onAppClick: (AppItem) -> Unit,
    onReorder: (List<AppItem>) -> Unit,
    onMoveToHome: (AppItem, Int, Int, Int) -> Unit,
    onRemoveFromDock: (AppItem) -> Unit = {},
    modifier: Modifier = Modifier,
    onAppMenu: (AppItem, IntOffset) -> Unit = { _, _ -> },
) {
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    // The lift [draggingIndex]/[dragOffsetX] belong to. After HOME cancels a lift, a still-held old
    // finger must neither move nor reset a newer lift's dock state.
    var liftToken by remember { mutableStateOf<Any?>(null) }
    // Each item's top-left in root coords, so a drag can be reported to the controller in root space.
    val itemRoots = remember { mutableStateMapOf<Int, Offset>() }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // The per-icon pointerInput block outlives recomposition (its keys don't include the order), so a
    // reorder must build on the *latest* list: an icon that stayed put kept the list captured before
    // its neighbours moved, and dragging it afterwards silently undid that earlier reorder.
    val latestApps by rememberUpdatedState(apps)
    // The dock leaves composition when it is switched off in settings; without this its last bounds
    // stayed in the controller, so the workspace cells that expanded into that area still hit-tested
    // as "over the dock" and rejected every drop (and swallowed the gestures that started there).
    DisposableEffect(dragController) {
        onDispose { dragController.dockBounds = Rect.Zero }
    }
    // Icon size derived from the slot so 6–7 dock icons fit a narrow screen (fixed 52dp overlapped).
    // The user's icon-size setting scales the nominal size before the slot clamp.
    val nominalIconSize = DOCK_ICON_SIZE * LocalIconScale.current
    val dockIconSize = if (rowWidthPx > 0 && apps.isNotEmpty()) {
        iconSizeForCell(with(density) { (rowWidthPx.toFloat() / apps.size).toDp() }, nominalIconSize)
    } else {
        nominalIconSize
    }

    val shape = RoundedCornerShape(30.dp)
    // Live drop feedback: highlight when a home icon hovers the dock and there's room (Launcher3's
    // onDragEnter/acceptDrop). derivedStateOf so we recompose only when crossing in/out, not per frame.
    val highlighted by remember {
        derivedStateOf {
            dragController.isIncomingFrom(DragSource.Home, DragSource.Drawer, DragSource.Folder) &&
                dragController.dockHasSpace &&
                dragController.isOverDock(dragController.rootPosition)
        }
    }

    Surface(
        modifier = modifier
            .onGloballyPositioned { dragController.dockBounds = it.boundsInRoot() }
            .border(2.dp, Color.White.copy(alpha = if (highlighted) 0.8f else 0f), shape),
        color = Color.Black.copy(alpha = backgroundAlpha),
        shape = shape,
    ) {
        Row(
            // Keep a full-height bar even when empty (no favorites yet) so apps can be dragged in.
            modifier = Modifier
                .heightIn(min = DOCK_ROW_MIN_HEIGHT)
                .padding(horizontal = 8.dp, vertical = DOCK_ROW_VERTICAL_PADDING)
                .onSizeChanged { rowWidthPx = it.width },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            apps.forEachIndexed { index, app ->
                val isDragging = index == draggingIndex
                // The whole slot takes the tap; the launch zooms from the icon in it.
                val launchOrigin = rememberAppLaunchOrigin()
                // The gesture below outlives a label or icon change of the app (its keys do not
                // change) and must act on the item as it is NOW, not as it was at the first touch.
                val currentApp by rememberUpdatedState(app)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .appLaunchTouch(app, launchOrigin)
                        .onGloballyPositioned { itemRoots[index] = it.positionInRoot() }
                        // Hide the in-dock copy only once it's moving; HomeScreen draws the floating
                        // copy. While merely lifted (menu showing) it stays visible.
                        .graphicsLayer { alpha = if (isDragging && dragController.moving) 0f else 1f }
                        // One unified gesture (like Workspace): quick tap launches; a still long-press
                        // lifts → drag (reorder / drop onto home) or, with no movement, opens the menu.
                        // A single detector avoids the tap-vs-long-press conflict that fired both.
                        .pointerInput(app.key, apps.size, rowWidthPx, locked) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // Claim the gesture immediately (same as Workspace icons): a dock touch
                                // belongs to the icon, so nothing else can steal it — not the pager, not
                                // finger jitter during the long-press hold. Without this the long-press
                                // was flaky ("had to try several times to get the popup"): the tiniest
                                // drift leaked out and cancelled the hold before it could open the menu.
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
                                liftToken = token
                                draggingIndex = index
                                dragOffsetX = 0f
                                val dragStartPx = IconPress.DragStartThreshold.toPx()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                // The menu opens now, above the item, under the still-held finger
                                // (issue #12); pulling the icon on closes it and the drag goes on.
                                run {
                                    val slotW = if (apps.isNotEmpty()) rowWidthPx.toFloat() / apps.size else 0f
                                    val anchor = Offset(itemRoot.x + slotW / 2f, itemRoot.y)
                                    onAppMenu(currentApp, IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()))
                                }
                                try {
                                    val completed = dragPointer(down.id) { change ->
                                        // Accumulate the delta BEFORE consuming: positionChange() returns
                                        // Offset.Zero once the change is consumed, which silently zeroed
                                        // dragOffsetX and broke in-dock reordering (the drag-out path still
                                        // worked because it uses the absolute change.position below).
                                        val dx = change.positionChange().x
                                        change.consume()
                                        if (!dragController.owns(token)) return@dragPointer
                                        dragOffsetX += dx
                                        // Only promote to a real move once the finger is pulled past the
                                        // drag-start threshold FROM WHERE IT WAS PICKED UP. A drift during a
                                        // static long-press must stay a long-press so the popup (app
                                        // shortcuts) opens instead of a no-op reorder + 2nd haptic. Summing
                                        // the travelled distance let plain finger jitter cross it.
                                        if (!dragController.moving &&
                                            IconPress.startsDrag(change.position, holdPos, dragStartPx)
                                        ) {
                                            dragController.beginMove()
                                        }
                                        if (dragController.moving) {
                                            dragController.update((itemRoots[index] ?: itemRoot) + change.position)
                                        }
                                    }
                                    // Nothing on release once HOME cancelled the lift.
                                    val live = completed && dragController.owns(token)
                                    if (live && dragController.moving) {
                                        val rootPos = dragController.rootPosition
                                        if (dragController.isOverRemove(rootPos)) {
                                            onRemoveFromDock(currentApp)
                                        } else if (dragController.isOverGrid(rootPos)) {
                                            val (page, cx, cy) = dragController.cellAt(rootPos)
                                            onMoveToHome(currentApp, page, cx, cy)
                                        } else {
                                            val current = latestApps
                                            val from = current.indexOfFirst { it.key == currentApp.key }
                                            val slot = if (current.isNotEmpty()) rowWidthPx.toFloat() / current.size else 1f
                                            val shift = if (slot > 0f) (dragOffsetX / slot).roundToInt() else 0
                                            val target = (from + shift).coerceIn(0, (current.size - 1).coerceAtLeast(0))
                                            if (from >= 0 && target != from) {
                                                val reordered = current.toMutableList().also {
                                                    it.add(target, it.removeAt(from))
                                                }
                                                onReorder(reordered)
                                            }
                                        }
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    // A still release leaves the menu, opened at the lift, as it is.
                                } finally {
                                    // Reset even on cancellation (node disposed / pointerInput restarted),
                                    // mirroring the local-drag path in Workspace, so a dead gesture can't
                                    // leave the shared controller lifted.
                                    dragController.stop(token)
                                    if (liftToken === token) {
                                        draggingIndex = -1
                                        dragOffsetX = 0f
                                        liftToken = null
                                    }
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
        }
    }
}

/** The dock's icon size, shrunk only when its apps don't fit the row at it. */
internal val DOCK_ICON_SIZE = 52.dp

/** The dock row's least height (it stays a full bar while empty) and its space above and below the icons. */
internal val DOCK_ROW_MIN_HEIGHT = 72.dp
internal val DOCK_ROW_VERTICAL_PADDING = 10.dp
