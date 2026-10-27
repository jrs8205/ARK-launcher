package org.arkikeskus.launcher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Long-press tolerances shared by every app icon (home, dock, drawer), after AOSP Launcher3
 * (Apache-2.0): `CheckLongPressHelper` keeps a hold alive while the finger stays on the icon (its
 * bounds plus the touch slop), and a lifted icon starts dragging only once it is pulled
 * `deep_shortcuts_start_drag_threshold` (16dp) from where it was picked up — until then a release
 * opens the long-press menu. Measuring from the start point (not the distance travelled) keeps
 * finger jitter during a still hold from ever turning into a move.
 */
object IconPress {
    val DragStartThreshold = 16.dp

    fun staysOnIcon(position: Offset, size: IntSize, slop: Float): Boolean =
        position.x >= -slop && position.y >= -slop &&
            position.x <= size.width + slop && position.y <= size.height + slop

    fun startsDrag(position: Offset, origin: Offset, thresholdPx: Float): Boolean =
        (position - origin).getDistance() > thresholdPx
}

/** How the press phase of an icon touch ended, before any long-press pickup ([awaitIconPress]). */
sealed interface IconPressOutcome {
    /** A clean finger-up on the icon that nothing else claimed: launch / open. */
    data object Tap : IconPressOutcome

    /**
     * The press is no longer the icon's: a parent took the gesture (the root swipe-up detector in
     * the Initial pass, the pager past its slop — the Compose counterpart of ACTION_CANCEL), the
     * finger left the icon (bounds + slop), or it vanished.
     */
    data object Cancelled : IconPressOutcome

    /** The finger stayed on the icon for the long-press timeout; [position] is where it is now. */
    data class Held(val position: Offset) : IconPressOutcome
}

/**
 * The press phase of a home icon, folder or shortcut after its (already consumed) [down].
 *
 * The moves are deliberately left unconsumed until the hold completes: a swipe that starts on an
 * icon then belongs to whoever claims it past the slop — the root swipe-up/-down detector or the
 * pager — exactly as on empty space, and the icon sees that claim as [IconPressOutcome.Cancelled].
 * Consuming them instead made a quick flick that lifted inside the icon's cell count as a tap
 * (the drawer opened AND the app underneath launched) and delayed a sideways page swipe until
 * the finger had left the cell. A hold still tolerates a roll anywhere on the icon.
 */
suspend fun AwaitPointerEventScope.awaitIconPress(down: PointerInputChange): IconPressOutcome {
    val slop = viewConfiguration.touchSlop
    var holdPos = down.position
    val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var ended: IconPressOutcome? = null
        while (ended == null) {
            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            ended = when {
                c == null || c.isConsumed || !IconPress.staysOnIcon(c.position, size, slop) ->
                    IconPressOutcome.Cancelled
                !c.pressed -> {
                    c.consume()
                    IconPressOutcome.Tap
                }
                else -> {
                    holdPos = c.position
                    // A parent that claims the gesture in the Main pass (the pager, past its slop)
                    // consumes AFTER this child — only the Final pass shows it, as in Compose's own
                    // tap and drag detectors.
                    awaitPointerEvent(PointerEventPass.Final)
                    if (c.isConsumed) IconPressOutcome.Cancelled else null
                }
            }
        }
        ended
    }
    return ended ?: IconPressOutcome.Held(holdPos)
}

/**
 * The move phase of a lifted icon: [onDrag] for every move of the pointer [pointerId], until it
 * rises (true) or something consumes it (false: the HOME key, a cancel). Unlike Compose's drag(),
 * this never hands the gesture over to another finger pressed on the same node when the tracked one
 * lifts: that finger could otherwise carry on moving the first finger's app, and drop it wherever it
 * rose. Shared by the home icons, the folder and shortcut drags, the dock and the folder card.
 */
suspend fun AwaitPointerEventScope.dragPointer(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): Boolean {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: return false
        if (change.isConsumed) return false
        if (change.changedToUpIgnoreConsumed()) return true
        if (change.positionChangedIgnoreConsumed()) onDrag(change)
    }
}
