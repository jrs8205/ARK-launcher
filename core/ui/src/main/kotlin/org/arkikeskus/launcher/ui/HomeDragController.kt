package org.arkikeskus.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.drop
import org.arkikeskus.launcher.model.AppItem

/** Which surface an in-progress drag was picked up from. */
enum class DragSource { Home, Dock, Drawer, Folder }

/** Where a drop on the dock lands (see [HomeDragController.dockTargetAt]). */
sealed interface DockTarget {
    /** Onto the tile at [index] (its middle half): an app there folds with it, a folder takes it in. */
    data class Onto(val index: Int) : DockTarget

    /** Between tiles, before the tile at [index] (= the item count: after the last one). */
    data class Between(val index: Int) : DockTarget
}

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

    /**
     * The folder (its home_items id) a long-press lifted instead of an app: a dock folder on its way
     * to the home grid, or a home folder on its way to the dock. Never set together with
     * [draggedApp]. The shell draws no floating copy for it (that is the lifting surface's, or the
     * home screen's, job); the drop cues follow [moving] / [leftSource] like an app's.
     */
    var draggedFolderId by mutableStateOf<Long?>(null)
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

    /**
     * How many lifts have started moving. [moving] can turn true and back to false before an observer
     * looks — the move past the threshold and the release handled in one input batch — while this
     * count still says a move began ([DismissWhenDragMoves]).
     */
    var moveStarts by mutableIntStateOf(0)
        private set

    /**
     * True once the finger has left the surface it lifted from — the drag is over the HOME screen
     * and its drop cues (the Workspace ring and edge page flips, the dock highlight, the remove pill)
     * may show. Home, dock and drawer drags leave with their first move ([beginMove]); a drag inside
     * the open folder's card moves without leaving (a reorder) until it crosses the card's edge
     * ([markLeftSource]), so nothing lights up or flips behind the card.
     */
    var leftSource by mutableStateOf(false)
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

    /**
     * The home folder whose icon the finger went down on, from that DOWN until its gesture ends (set and
     * cleared by the grid's folder tiles). The root swipe detector reads it to route a swipe up that starts
     * on a folder to the folder's first app when that setting is on (issue #17). Read only inside pointer
     * loops, never composed, so plain state is enough.
     */
    var pressedFolderId: Long? = null

    // Home-grid metrics for cell math on a dock→home / drawer→home drop (published by Workspace).
    var columns by mutableStateOf(1)
    var rows by mutableStateOf(1)
    var currentPage by mutableStateOf(0)

    // Published by HomeScreen so a home→dock / drawer→dock drop knows where/whether it can land.
    var dockItemCount by mutableStateOf(0)
    var dockHasSpace by mutableStateOf(false)

    /** Root-space bounds of the dock's shown slots in order (published by the Dock), so a drop can
     *  tell a tile from the gap beside it ([dockTargetAt]). Empty while the dock is hidden. */
    var dockSlots by mutableStateOf<List<Rect>>(emptyList())

    /**
     * Root-space bounds of placed widgets whose content is internally scrollable (collection widgets:
     * a ListView/GridView/StackView fed by a RemoteViewsService, e.g. WhatsApp's conversation list),
     * keyed by the placed widget's home_items row id. The root home swipe detector
     * ([isOverScrollableWidget]) skips a touch that starts inside one of these so the widget's own list
     * scrolls instead of the gesture opening the app drawer — the Compose equivalent of Launcher3's
     * `LauncherAppWidgetHostView` calling `requestDisallowInterceptTouchEvent(true)` when scrollable.
     */
    val scrollableWidgetRects = mutableStateMapOf<Long, Rect>()

    val isDragging: Boolean get() = draggedApp != null || draggedFolderId != null

    /** True if [p] (root coords) is inside a placed widget that scrolls its own content. */
    fun isOverScrollableWidget(p: Offset): Boolean =
        scrollableWidgetRects.values.any { !it.isEmpty && it.contains(p) }

    /**
     * The gesture holding the current lift — an app icon on any surface, or a home folder/shortcut.
     * A second finger's long-press is refused while one is held: it used to overwrite this shared
     * state, so the first finger's release dropped the OTHER item. Same rule as WidgetDragController.
     */
    private var owner: Any? = null

    /** Claims the lift for [token]; false while another gesture holds it. */
    fun claim(token: Any): Boolean {
        if (owner != null && owner !== token) return false
        owner = token
        return true
    }

    /** False once the lift was cancelled ([cancel]) — the gesture must then drop nothing on release. */
    fun owns(token: Any): Boolean = owner === token

    fun release(token: Any) {
        if (owner === token) owner = null
    }

    /** Lift [app] (long-press) for [token] — menu shows; not yet "moving". False if refused. */
    fun start(app: AppItem, from: DragSource, root: Offset, token: Any): Boolean {
        if (!claim(token)) return false
        draggedApp = app
        draggedFolderId = null
        source = from
        rootPosition = root
        moving = false
        leftSource = false
        return true
    }

    /** Lift the folder [folderId] (long-press) for [token] — see [draggedFolderId]. False if refused. */
    fun startFolder(folderId: Long, from: DragSource, root: Offset, token: Any): Boolean {
        if (!claim(token)) return false
        draggedApp = null
        draggedFolderId = folderId
        source = from
        rootPosition = root
        moving = false
        leftSource = false
        return true
    }

    fun update(root: Offset) {
        rootPosition = root
    }

    /** The lifted icon started moving → becomes a real drag (floating icon + bar). [leftSource] is
     *  false only for a move that stays on its own surface (a reorder inside the folder card). */
    fun beginMove(leftSource: Boolean = true) {
        moving = true
        moveStarts++
        this.leftSource = leftSource
    }

    /** The finger crossed the source surface's edge: from here on the drag is over the home screen. */
    fun markLeftSource() {
        leftSource = true
    }

    /** True while a drag from one of [sources] is moving over the home screen (see [leftSource]). */
    fun isIncomingFrom(vararg sources: DragSource): Boolean = moving && leftSource && source in sources

    fun stop(token: Any) {
        if (owner !== token) return
        draggedApp = null
        draggedFolderId = null
        moving = false
        leftSource = false
        owner = null
    }

    /** HOME: ends whatever lift is held; its gesture sees [owns] turn false and drops nothing. */
    fun cancel() {
        draggedApp = null
        draggedFolderId = null
        moving = false
        leftSource = false
        localDragging = false
        localGestureActive = false
        owner = null
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

    /** Dock insert index under [p] (root coords) — for a home→dock drop: the slot whose middle the
     *  finger has passed, from the published slots, or from the item count while none are published. */
    fun dockIndexAt(p: Offset): Int {
        if (dockSlots.isNotEmpty()) return dockSlots.count { it.center.x < p.x }
        if (dockBounds.isEmpty || dockItemCount <= 0) return dockItemCount
        val slot = dockBounds.width / dockItemCount
        return ((p.x - dockBounds.left) / slot).toInt().coerceIn(0, dockItemCount)
    }

    /** Where a drop at [p] (root coords) on the dock lands: onto the tile whose middle half holds the
     *  finger (Launcher3's folder-creation zone), else between tiles at [dockIndexAt]. */
    fun dockTargetAt(p: Offset): DockTarget {
        dockSlots.forEachIndexed { i, r ->
            if (r.isEmpty || !r.contains(p)) return@forEachIndexed
            val margin = r.width / 4f
            if (p.x >= r.left + margin && p.x <= r.right - margin) return DockTarget.Onto(i)
        }
        return DockTarget.Between(dockIndexAt(p))
    }

    /** True while a drop at [p] on the dock has somewhere to land: a gap while the dock has room,
     *  or a tile (which takes an app in even when the dock is full). Only apps land on tiles. */
    fun dockAccepts(p: Offset): Boolean =
        isOverDock(p) && (dockHasSpace || (draggedApp != null && dockTargetAt(p) is DockTarget.Onto))
}

@Composable
fun rememberHomeDragController(): HomeDragController = remember { HomeDragController() }

/**
 * Calls [onDismiss] when a lifted icon starts to move. An icon's menu opens the moment the icon lifts
 * under the still-held finger (issue #12, as in Pixel Launcher); pulling the icon on from there closes
 * the menu and the drag goes on. Watches [HomeDragController.moveStarts], not `moving`: a move and its
 * release in one input batch are over before this looks.
 */
@Composable
fun DismissWhenDragMoves(controller: HomeDragController, onDismiss: () -> Unit) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(controller) {
        snapshotFlow { controller.moveStarts }.drop(1).collect { currentOnDismiss() }
    }
}
