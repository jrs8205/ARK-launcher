package org.arkikeskus.launcher.feature.home

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.arkikeskus.launcher.model.WidgetPlacement

internal fun minimumWidgetCells(sizeDp: Float, cellDp: Float): Int =
    ceil(sizeDp.coerceAtLeast(0f) / cellDp.coerceAtLeast(1f)).toInt().coerceAtLeast(1)

internal fun maximumWidgetCells(sizeDp: Float, cellDp: Float, gridCells: Int): Int =
    if (sizeDp <= 0f) gridCells else
        floor(sizeDp / cellDp.coerceAtLeast(1f)).toInt().coerceIn(1, gridCells.coerceAtLeast(1))

/** Preserve the opposite edge, including old layouts smaller than today's provider minimum. */
internal fun resizeWidgetStartEdge(
    position: Int, span: Int, delta: Int, minimum: Int, maximum: Int,
): Pair<Int, Int>? {
    val end = position + span
    val largest = minOf(maximum, end)
    if (minimum > largest) return null
    val nextSpan = (span - delta).coerceIn(minimum, largest)
    return (end - nextSpan) to nextSpan
}

/** The finger must be on the grid; clamp the footprint, not the finger, at its edges. */
internal fun widgetDropPlacement(
    page: Int, x: Float, y: Float, width: Float, height: Float,
    columns: Int, rows: Int, spanX: Int, spanY: Int, grabX: Float, grabY: Float,
): WidgetPlacement? {
    if (columns < 1 || rows < 1 || width <= 0 || height <= 0 ||
        spanX !in 1..columns || spanY !in 1..rows ||
        x < 0 || y < 0 || x >= width || y >= height
    ) return null
    val cx = (x / (width / columns) - grabX * spanX).roundToInt().coerceIn(0, columns - spanX)
    val cy = (y / (height / rows) - grabY * spanY).roundToInt().coerceIn(0, rows - spanY)
    return WidgetPlacement(page, cx, cy, spanX, spanY)
}
