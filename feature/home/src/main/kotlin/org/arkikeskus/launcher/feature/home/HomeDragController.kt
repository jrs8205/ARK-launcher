package org.arkikeskus.launcher.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.arkikeskus.launcher.model.AppItem

/** Which surface an in-progress drag was picked up from. */
enum class DragSource { Home, Dock }

/** Actions offered by the drag drop-target bar (Launcher3's DropTargetBar). */
enum class DropAction { Remove, Info, Uninstall }

/**
 * Shared drag state hoisted to `HomeScreen` — the Compose equivalent of Launcher3's drag layer +
 * drag controller. Compose can't transfer a pointer gesture between nodes mid-drag, so the source
 * surface (Workspace or Dock) keeps owning the gesture and feeds the finger's **root** position
 * here; `HomeScreen` draws the single floating icon, and each surface hit-tests its drop against
 * [gridBounds] / [dockBounds].
 *
 * Geometry is all in root coordinates so the two surfaces and the overlay share one space.
 */
class HomeDragController {
    var draggedApp by mutableStateOf<AppItem?>(null)
        private set
    var source by mutableStateOf(DragSource.Home)
        private set
    var rootPosition by mutableStateOf(Offset.Zero)
        private set

    // Drop-target geometry, published by the surfaces via onGloballyPositioned.
    var gridBounds by mutableStateOf(Rect.Zero)
    var dockBounds by mutableStateOf(Rect.Zero)

    // Home-grid metrics for cell math on a dock→home drop (published by Workspace).
    var columns by mutableStateOf(1)
    var rows by mutableStateOf(1)
    var currentPage by mutableStateOf(0)

    // Published by HomeScreen so a home→dock drop knows where/whether it can land.
    var dockItemCount by mutableStateOf(0)
    var dockHasSpace by mutableStateOf(false)

    // Drop-target bar button bounds (root coords), published by the bar's buttons.
    var removeBounds by mutableStateOf(Rect.Zero)
    var infoBounds by mutableStateOf(Rect.Zero)
    var uninstallBounds by mutableStateOf(Rect.Zero)

    val isDragging: Boolean get() = draggedApp != null

    fun start(app: AppItem, from: DragSource, root: Offset) {
        draggedApp = app
        source = from
        rootPosition = root
    }

    fun update(root: Offset) {
        rootPosition = root
    }

    fun stop() {
        draggedApp = null
    }

    /** Which drop-bar action is under [p] (root coords), or null. Bar wins over dock/grid. */
    fun barActionAt(p: Offset): DropAction? = when {
        !isDragging -> null
        !removeBounds.isEmpty && removeBounds.contains(p) -> DropAction.Remove
        !infoBounds.isEmpty && infoBounds.contains(p) -> DropAction.Info
        !uninstallBounds.isEmpty && uninstallBounds.contains(p) -> DropAction.Uninstall
        else -> null
    }

    fun isOverDock(p: Offset): Boolean = !dockBounds.isEmpty && dockBounds.contains(p)

    fun isOverGrid(p: Offset): Boolean = !gridBounds.isEmpty && gridBounds.contains(p)

    /** Home cell (page, cellX, cellY) under [p] (root coords) — for a dock→home drop. */
    fun cellAt(p: Offset): Triple<Int, Int, Int> {
        val cellW = if (columns > 0 && gridBounds.width > 0f) gridBounds.width / columns else 1f
        val cellH = if (rows > 0 && gridBounds.height > 0f) gridBounds.height / rows else 1f
        val cx = ((p.x - gridBounds.left) / cellW).toInt().coerceIn(0, columns - 1)
        val cy = ((p.y - gridBounds.top) / cellH).toInt().coerceIn(0, rows - 1)
        return Triple(currentPage, cx, cy)
    }

    /** Dock insert index under [p] (root coords) — for a home→dock drop. */
    fun dockIndexAt(p: Offset): Int {
        if (dockBounds.isEmpty || dockItemCount <= 0) return dockItemCount
        val slot = dockBounds.width / dockItemCount
        return ((p.x - dockBounds.left) / slot).toInt().coerceIn(0, dockItemCount)
    }
}

@Composable
fun rememberHomeDragController(): HomeDragController = remember { HomeDragController() }
