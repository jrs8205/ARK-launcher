package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.component.AppIcon
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
    backgroundAlpha: Float,
    locked: Boolean,
    dragController: HomeDragController,
    onAppClick: (AppItem) -> Unit,
    onReorder: (List<AppItem>) -> Unit,
    onMoveToHome: (AppItem, Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onAppMenu: (AppItem, IntOffset) -> Unit = { _, _ -> },
) {
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    // Each item's top-left in root coords, so a drag can be reported to the controller in root space.
    val itemRoots = remember { mutableStateMapOf<Int, Offset>() }
    val haptics = LocalHapticFeedback.current

    val shape = RoundedCornerShape(30.dp)
    // Live drop feedback: highlight when a home icon hovers the dock and there's room (Launcher3's
    // onDragEnter/acceptDrop). derivedStateOf so we recompose only when crossing in/out, not per frame.
    val highlighted by remember {
        derivedStateOf {
            dragController.moving &&
                (dragController.source == DragSource.Home || dragController.source == DragSource.Drawer) &&
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
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .onSizeChanged { rowWidthPx = it.width },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            apps.forEachIndexed { index, app ->
                val isDragging = index == draggingIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
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
                                val slop = viewConfiguration.touchSlop
                                var tapped = false
                                var abandoned = false
                                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val c = ev.changes.firstOrNull { it.id == down.id }
                                        if (c == null) {
                                            abandoned = true
                                            return@withTimeoutOrNull
                                        }
                                        if (!c.pressed) {
                                            tapped = true
                                            return@withTimeoutOrNull
                                        }
                                        if ((c.position - down.position).getDistance() > slop) {
                                            abandoned = true
                                            return@withTimeoutOrNull
                                        }
                                    }
                                }
                                if (tapped) {
                                    onAppClick(app)
                                    return@awaitEachGesture
                                }
                                if (abandoned) return@awaitEachGesture
                                // Desktop locked: long-press does nothing (no menu, no lift); tap still launches.
                                if (locked) return@awaitEachGesture
                                // LONG PRESS → lift
                                draggingIndex = index
                                dragOffsetX = 0f
                                val itemRoot = itemRoots[index] ?: Offset.Zero
                                dragController.start(app, DragSource.Dock, itemRoot + down.position)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val completed = drag(down.id) { change ->
                                    // Accumulate the delta BEFORE consuming: positionChange() returns
                                    // Offset.Zero once the change is consumed, which silently zeroed
                                    // dragOffsetX and broke in-dock reordering (the drag-out path still
                                    // worked because it uses the absolute change.position below).
                                    dragOffsetX += change.positionChange().x
                                    change.consume()
                                    if (!dragController.moving) dragController.beginMove()
                                    dragController.update((itemRoots[index] ?: itemRoot) + change.position)
                                }
                                if (completed && dragController.moving) {
                                    val rootPos = dragController.rootPosition
                                    if (dragController.isOverGrid(rootPos)) {
                                        val (page, cx, cy) = dragController.cellAt(rootPos)
                                        onMoveToHome(app, page, cx, cy)
                                    } else {
                                        val slot = if (apps.isNotEmpty()) rowWidthPx.toFloat() / apps.size else 1f
                                        val shift = if (slot > 0f) (dragOffsetX / slot).roundToInt() else 0
                                        val target = (index + shift).coerceIn(0, apps.size - 1)
                                        if (target != index) {
                                            val reordered = apps.toMutableList().also {
                                                it.add(target, it.removeAt(index))
                                            }
                                            onReorder(reordered)
                                        }
                                    }
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else if (completed) {
                                    // No movement → static long-press → show the menu above the item.
                                    val slotW = if (apps.isNotEmpty()) rowWidthPx.toFloat() / apps.size else 0f
                                    val anchor = Offset(itemRoot.x + slotW / 2f, itemRoot.y)
                                    onAppMenu(app, IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()))
                                }
                                dragController.stop()
                                draggingIndex = -1
                                dragOffsetX = 0f
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(
                        appItem = app,
                        labelColor = Color.White,
                        showLabel = showLabels,
                        iconSize = 52.dp,
                        badgeCount = badges[app.badgeKey] ?: 0,
                        badgeShowCount = badgeShowCount,
                        badgeScale = badgeScale,
                    )
                }
            }
        }
    }
}
