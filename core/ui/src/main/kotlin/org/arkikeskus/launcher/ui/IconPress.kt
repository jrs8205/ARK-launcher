package org.arkikeskus.launcher.ui

import androidx.compose.ui.geometry.Offset
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
