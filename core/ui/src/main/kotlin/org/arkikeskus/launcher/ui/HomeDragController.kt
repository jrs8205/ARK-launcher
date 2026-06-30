package org.arkikeskus.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.arkikeskus.launcher.model.AppItem

/** Which surface an in-progress drag was picked up from. */
enum class DragSource { Home, Dock, Drawer }

/**
 * Shared drag state hoisted to the launcher shell — the Compose equivalent of Launcher3's drag layer +
 * drag controller. Compose can't transfer a pointer gesture between nodes mid-drag, so the source
 * surface (Workspace, Dock, or the app drawer) keeps owning the gesture and feeds the finger's
 * **root** position here; the shell draws the single floating icon, and each surface hit-tests its
 * drop against [gridBounds] / [dockBounds].
 *
 * Geometry is all in root coordinates so the surfaces and the overlay share one space.
 */
class HomeDragController {
    var draggedApp by mutableStateOf<AppItem?>(null)
        private set
    var source by mutableStateOf(DragSource.Home)
        private set
    var rootPosition by mutableStateOf(Offset.Zero)
        private set

    /**
     * True once a lifted icon actually starts moving. While false the icon is merely "lifted" and the
     * long-press menu is showing; the floating icon and drop-target bar only appear once moving — so
     * the menu and the bar never show at the same time.
     */
    var moving by mutableStateOf(false)
        private set

    // Drop-target geometry, published by the surfaces via onGloballyPositioned.
    var gridBounds by mutableStateOf(Rect.Zero)
    var dockBounds by mutableStateOf(Rect.Zero)

    // The top "remove" drop zone (published by the overlay). [localDragging] is set while a removable
    // local drag (a pinned shortcut) is moving, so the same remove zone can show for it too.
    var removeBounds by mutableStateOf(Rect.Zero)
    var localDragging by mutableStateOf(false)

    /**
     * True while a non-AppItem local home entry — a folder or a pinned shortcut — owns a long-press
     * gesture (from pickup until release). The root-level home swipe detector checks this so it never
     * steals the first movement after such a long-press. [localDragging] is not enough on its own: it
     * is only set once a *removable* local drag actually starts moving, so it misses folders entirely
     * and the lifted-but-not-yet-moving window of a shortcut.
     */
    var localGestureActive by mutableStateOf(false)

    // Home-grid metrics for cell math on a dock→home / drawer→home drop (published by Workspace).
    var columns by mutableStateOf(1)
    var rows by mutableStateOf(1)
    var currentPage by mutableStateOf(0)

    // Published by HomeScreen so a home→dock / drawer→dock drop knows where/whether it can land.
    var dockItemCount by mutableStateOf(0)
    var dockHasSpace by mutableStateOf(false)

    /**
     * Root-space bounds of placed widgets whose content is internally scrollable (collection widgets:
     * a ListView/GridView/StackView fed by a RemoteViewsService, e.g. WhatsApp's conversation list),
     * keyed by the placed widget's home_items row id. The root home swipe detector
     * ([isOverScrollableWidget]) skips a touch that starts inside one of these so the widget's own list
     * scrolls instead of the gesture opening the app drawer — the Compose equivalent of Launcher3's
     * `LauncherAppWidgetHostView` calling `requestDisallowInterceptTouchEvent(true)` when scrollable.
     */
    val scrollableWidgetRects = mutableStateMapOf<Long, Rect>()

    val isDragging: Boolean get() = draggedApp != null

    /** True if [p] (root coords) is inside a placed widget that scrolls its own content. */
    fun isOverScrollableWidget(p: Offset): Boolean =
        scrollableWidgetRects.values.any { !it.isEmpty && it.contains(p) }

    /** Lift [app] (long-press) — menu shows; not yet "moving". */
    fun start(app: AppItem, from: DragSource, root: Offset) {
        draggedApp = app
        source = from
        rootPosition = root
        moving = false
    }

    fun update(root: Offset) {
        rootPosition = root
    }

    /** The lifted icon started moving → becomes a real drag (floating icon + bar). */
    fun beginMove() {
        moving = true
    }

    fun stop() {
        draggedApp = null
        moving = false
    }

    fun isOverDock(p: Offset): Boolean = !dockBounds.isEmpty && dockBounds.contains(p)

    fun isOverGrid(p: Offset): Boolean = !gridBounds.isEmpty && gridBounds.contains(p)

    fun isOverRemove(p: Offset): Boolean = !removeBounds.isEmpty && removeBounds.contains(p)

    /** Home cell (page, cellX, cellY) under [p] (root coords) — for a dock/drawer→home drop. */
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
