package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon
import kotlin.math.abs
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
    var dragDist by remember { mutableFloatStateOf(0f) }
    // Each item's top-left in root coords, so a drag can be reported to the controller in root space.
    val itemRoots = remember { mutableStateMapOf<Int, Offset>() }
    val haptics = LocalHapticFeedback.current
    // Below this sideways travel, a long-press is treated as "open menu" rather than a reorder.
    val menuThresholdPx = with(LocalDensity.current) { 18.dp.toPx() }
    // One-shot guard so the "reorder started" tick fires once per drag (not every frame).
    val reorderArmed = remember { BooleanArray(1) }

    val shape = RoundedCornerShape(30.dp)
    // Live drop feedback: highlight when a home icon hovers the dock and there's room (Launcher3's
    // onDragEnter/acceptDrop). derivedStateOf so we recompose only when crossing in/out, not per frame.
    val highlighted by remember {
        derivedStateOf {
            dragController.moving &&
                dragController.source == DragSource.Home &&
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
                        .pointerInput(app.key, apps.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    draggingIndex = index
                                    dragOffsetX = 0f
                                    dragDist = 0f
                                    reorderArmed[0] = false
                                    val root = (itemRoots[index] ?: Offset.Zero) + offset
                                    dragController.start(app, DragSource.Dock, root)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffsetX += amount.x
                                    dragDist += amount.getDistance()
                                    val root = (itemRoots[index] ?: Offset.Zero) + change.position
                                    dragController.update(root)
                                    // Past the threshold this is a real drag (dismisses the menu).
                                    if (!dragController.moving && dragDist > menuThresholdPx) {
                                        dragController.beginMove()
                                    }
                                    // Tick once when a sideways reorder clearly begins.
                                    if (!reorderArmed[0] && abs(dragOffsetX) >= menuThresholdPx) {
                                        reorderArmed[0] = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDragEnd = {
                                    if (!dragController.moving) {
                                        // Static long-press → show the menu anchored above the item.
                                        val slotW = if (apps.isNotEmpty()) rowWidthPx.toFloat() / apps.size else 0f
                                        val itemRoot = itemRoots[index] ?: Offset.Zero
                                        val anchor = Offset(itemRoot.x + slotW / 2f, itemRoot.y)
                                        onAppMenu(app, IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()))
                                    } else {
                                        val rootPos = dragController.rootPosition
                                        when {
                                            dragController.isOverGrid(rootPos) -> {
                                                val (page, cx, cy) = dragController.cellAt(rootPos)
                                                onMoveToHome(app, page, cx, cy)
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            else -> {
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
                                        }
                                    }
                                    dragController.stop()
                                    draggingIndex = -1
                                    dragOffsetX = 0f
                                },
                                onDragCancel = {
                                    dragController.stop()
                                    draggingIndex = -1
                                    dragOffsetX = 0f
                                },
                            )
                        }
                        .clickable { onAppClick(app) },
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
