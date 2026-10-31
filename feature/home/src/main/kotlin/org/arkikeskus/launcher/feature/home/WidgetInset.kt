package org.arkikeskus.launcher.feature.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A widget's margin inside its cells, sideways and up/down ([widgetInsets]). */
internal data class WidgetInsets(val horizontal: Dp, val vertical: Dp)

/**
 * The margin a widget keeps inside its cells when [alignWithIcons] is on (a Telegram request after 0.9.7):
 * sideways, the side margin an app icon of [iconSize] has inside a cell of [cellWidth], so the widget's edges
 * line up with the icons' instead of the cell edges -- what Launcher3's widget padding does. Up and down the
 * same, but never more than a quarter of [cellHeight]: a one-row widget keeps at least half its row (a short
 * cell on a dense grid would otherwise leave it no height at all). Off, none: the widget fills its span.
 */
internal fun widgetInsets(cellWidth: Dp, cellHeight: Dp, iconSize: Dp, alignWithIcons: Boolean): WidgetInsets {
    if (!alignWithIcons) return WidgetInsets(0.dp, 0.dp)
    val (h, v) = widgetInsetsDp(cellWidth.value, cellHeight.value, iconSize.value)
    return WidgetInsets(h.dp, v.dp)
}

/** [widgetInsets] in plain dp for the pin-request sizing: sideways then up/down. */
internal fun widgetInsetsDp(cellWidthDp: Float, cellHeightDp: Float, iconDp: Float): Pair<Float, Float> {
    val horizontal = ((cellWidthDp - iconDp) / 2f).coerceAtLeast(0f)
    val vertical = minOf(horizontal, cellHeightDp / 4f).coerceAtLeast(0f)
    return horizontal to vertical
}

/**
 * [insets] for a widget placed in a span of [spanWidthDp] x [spanHeightDp]: never so much that it is left less
 * than [minWidthDp] x [minHeightDp], the smallest its provider supports. The setting does not touch the layout, so
 * a widget placed before it was on may fill its cells exactly: that one keeps its size rather than clip its content.
 */
internal fun insetsWithinMinimum(
    insets: WidgetInsets, spanWidthDp: Float, spanHeightDp: Float, minWidthDp: Float, minHeightDp: Float,
): WidgetInsets = WidgetInsets(
    minOf(insets.horizontal, ((spanWidthDp - minWidthDp) / 2f).coerceAtLeast(0f).dp),
    minOf(insets.vertical, ((spanHeightDp - minHeightDp) / 2f).coerceAtLeast(0f).dp),
)

/** The size (one axis, dp) a widget is told about once [insetDp] is taken off both sides; never below 1. */
internal fun insetWidgetSizeDp(spanDp: Int, insetDp: Int): Int = (spanDp - 2 * insetDp).coerceAtLeast(1)
