package org.arkikeskus.launcher.feature.home

import android.appwidget.AppWidgetProviderInfo
import android.graphics.Bitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.withTimeoutOrNull
import org.arkikeskus.launcher.model.WidgetPlacement
import org.arkikeskus.launcher.ui.HomeDragController

sealed interface WidgetChoice {
    data class App(val provider: AppWidgetProviderInfo) : WidgetChoice
    data class Builtin(val type: String) : WidgetChoice
}

internal data class EditingItem(
    val rowId: Long, val appWidgetId: Int?, val page: Int,
    val cellX: Int, val cellY: Int, val spanX: Int, val spanY: Int,
)

internal data class WidgetDrag(
    val item: EditingItem?,
    val choice: WidgetChoice?,
    val spanX: Int,
    val spanY: Int,
    val grabFraction: Offset,
    val preview: Bitmap?,
)

/** The source keeps owning its pointer, even while its pixels are hidden or its page moves. */
class WidgetDragController(private val home: HomeDragController) {
    internal var active by mutableStateOf<WidgetDrag?>(null)
        private set
    var moving by mutableStateOf(false)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set
    internal var target: (() -> WidgetPlacement?)? = null
    internal var onDrop: ((WidgetDrag, WidgetPlacement?) -> Unit)? = null
    internal var onStill: ((WidgetDrag) -> Unit)? = null
    internal var findSpace: ((Int, Int) -> WidgetPlacement?)? = null
    /** True while something else (the edit frame) still needs the root swipe-up to stay locked. */
    internal var keepsGestureLock: () -> Boolean = { false }
    internal var cellWidthDp by mutableFloatStateOf(1f)
    internal var cellHeightDp by mutableFloatStateOf(1f)

    /** The gesture that started the active drag; see [owns]. */
    private var owner: Any? = null

    /** Pointers a widget's own child claimed for its long-press (a people tile's menu), so the
     *  widget pickup underneath yields to it; see [claimsWidgetLongPress]. */
    private val claimedPointers = HashSet<PointerId>()

    internal fun claim(pointer: PointerId) { claimedPointers.add(pointer) }
    internal fun release(pointer: PointerId) { claimedPointers.remove(pointer) }
    internal fun isClaimed(pointer: PointerId): Boolean = pointer in claimedPointers

    /**
     * Every widget has its own detector but they share this controller, so two fingers on two widgets
     * used to let the later pickup replace the first drag while the first detector kept driving it
     * (its release dropped or cancelled the OTHER widget). A call that names an owner acts only on
     * its own drag; an owner-less call (Home, Back, dispose) is a deliberate force.
     */
    private fun owns(owner: Any?): Boolean = owner == null || owner === this.owner

    /** False when another gesture already owns a drag — the caller must then leave the touch alone. */
    internal fun start(drag: WidgetDrag, root: Offset, owner: Any? = null): Boolean {
        if (active != null) return false
        this.owner = owner
        active = drag
        position = root
        moving = false
        home.localGestureActive = true
        return true
    }

    internal fun isOwnedBy(owner: Any): Boolean = active != null && owner === this.owner

    fun move(root: Offset, owner: Any? = null) {
        if (active == null || !owns(owner)) return
        moving = true
        position = root
        home.localDragging = true
        home.update(root)
    }

    fun finish(owner: Any? = null) {
        if (!owns(owner)) return
        val drag = active ?: return
        try {
            if (moving) onDrop?.invoke(drag, target?.invoke()) else onStill?.invoke(drag)
        } finally {
            cancel()
        }
    }

    fun cancel(owner: Any? = null) {
        if (!owns(owner)) return
        this.owner = null
        active = null
        moving = false
        home.localDragging = false
        // Not a blind reset: a drag that ends INSIDE the edit frame must leave the lock held, and the
        // keyed effect that re-asserts it would miss a lift+release landing in one frame.
        home.localGestureActive = keepsGestureLock()
    }
}

/**
 * Observe normal taps/scrolls without consuming them. After pickup, own the remaining stream in
 * Initial so an embedded Android widget receives CANCEL instead of a stray click on release.
 * Root coordinates remain stable when the source page scrolls underneath the finger.
 */
internal fun Modifier.widgetDragGesture(
    key: Any,
    enabled: Boolean,
    controller: WidgetDragController,
    immediate: Boolean = false,
    onLift: (root: Offset, fraction: Offset, owner: Any) -> Unit,
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentLift by rememberUpdatedState(onLift)
    onGloballyPositioned { coordinates = it }
        .pointerInput(key, immediate, controller) {
            awaitEachGesture {
                val down = awaitFirstDown(false, PointerEventPass.Initial)
                if (!currentEnabled) return@awaitEachGesture
                val initialCoordinates = coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val origin = initialCoordinates.localToRoot(down.position)
                val fraction = Offset(
                    (down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    (down.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                )
                if (!immediate) {
                    val releasedOrMoved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull true
                            if (!change.pressed || change.isConsumed ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) return@withTimeoutOrNull true
                        }
                    }
                    if (releasedOrMoved != null || !currentEnabled || controller.isClaimed(down.id)) return@awaitEachGesture
                }
                val token = Any()
                currentLift(origin, fraction, token)
                // Another finger already owns a drag: this touch stays an ordinary, unconsumed touch.
                if (!controller.isOwnedBy(token)) return@awaitEachGesture
                try {
                    while (controller.isOwnedBy(token)) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // ACTION_CANCEL (a system gesture taking over, the node being disposed) arrives as
                        // a synthetic release that is ALREADY consumed; nothing upstream consumes a real
                        // one in this pass. It ends the drag without dropping — checked before our own
                        // consume() below makes the two indistinguishable.
                        if (!change.pressed && change.isConsumed) break
                        val coords = coordinates?.takeIf { it.isAttached } ?: break
                        val root = coords.localToRoot(change.position)
                        val moving = controller.moving || (root - origin).getDistance() > viewConfiguration.touchSlop
                        event.changes.forEach { it.consume() }
                        if (moving) controller.move(root, token)
                        if (!change.pressed) {
                            controller.finish(token)
                            break
                        }
                    }
                } finally {
                    controller.cancel(token)
                }
            }
        }
}

/** The widget drag controller of the workspace hosting this built-in widget, for [claimsWidgetLongPress]. */
internal val LocalWidgetDragController = staticCompositionLocalOf<WidgetDragController?> { null }

/**
 * Marks a built-in widget's child as owning the long-press on it: the widget pickup underneath
 * observes in the Initial pass and would otherwise lift the whole widget on the same timeout the
 * child's own long-press fires on (and open the edit frame under the child's menu on release).
 * The claim is made on the down, long before either timeout, so it is never a race. Taps and
 * drags are unaffected: the pickup already yields to a release or a move before the timeout.
 */
internal fun Modifier.claimsWidgetLongPress(controller: WidgetDragController?): Modifier =
    if (controller == null) this else pointerInput(controller) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            controller.claim(down.id)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                }
            } finally {
                controller.release(down.id)
            }
        }
    }
