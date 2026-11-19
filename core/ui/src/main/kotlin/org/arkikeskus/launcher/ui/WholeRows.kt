package org.arkikeskus.launcher.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Keeps a scrolling list of rows, each [rowHeight] tall, to whole rows: where the room it is given ends inside
 * a row, the list takes only the rows that fit whole and leaves the rest of the room. An open folder's apps
 * were cut between an icon and its name, or through the name. A room smaller than one row is used as it is
 * (part of a row is more than nothing), and a list that is given no limit is not limited.
 *
 * Put it before the scrolling modifier, or on the lazy list itself.
 */
fun Modifier.wholeRows(rowHeight: Dp): Modifier = layout { measurable, constraints ->
    val row = rowHeight.roundToPx()
    val room = constraints.maxHeight
    val whole = if (constraints.hasBoundedHeight && row in 1..room) room / row * row else room
    val placeable = measurable.measure(constraints.copy(minHeight = minOf(constraints.minHeight, whole), maxHeight = whole))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The thumb's width, its distance from the list's edge and its least length. */
private val THUMB_WIDTH = 3.dp
private val THUMB_INSET = 2.dp
private val THUMB_MIN_LENGTH = 24.dp

/**
 * A thin thumb at the list's end edge while the list has more than it shows: where one is in it, and before
 * that, that there is more at all (a list of whole rows gives no other sign of it). Nothing is drawn for a
 * list that shows everything. Put it before the scrolling modifier, so it stays put while the content moves.
 */
fun Modifier.scrollThumb(state: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    val hidden = state.maxValue
    if (hidden <= 0 || hidden == Int.MAX_VALUE) return@drawWithContent
    drawThumb(color, shown = size.height, total = size.height + hidden, scrolled = state.value.toFloat())
}

/** [scrollThumb] for a lazy grid of [columns] columns whose rows are all one height. */
fun Modifier.scrollThumb(state: LazyGridState, columns: Int, color: Color): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
    val row = first.size.height.toFloat()
    if (row <= 0f || columns <= 0) return@drawWithContent
    val rows = (info.totalItemsCount + columns - 1) / columns
    drawThumb(color, shown = size.height, total = rows * row, scrolled = first.index / columns * row - first.offset.y)
}

private fun ContentDrawScope.drawThumb(color: Color, shown: Float, total: Float, scrolled: Float) {
    if (total <= shown + 1f) return
    val width = THUMB_WIDTH.toPx()
    val length = (shown * shown / total).coerceIn(minOf(THUMB_MIN_LENGTH.toPx(), shown), shown)
    val top = ((shown - length) * (scrolled / (total - shown))).coerceIn(0f, shown - length)
    val left = if (layoutDirection == LayoutDirection.Ltr) size.width - width - THUMB_INSET.toPx() else THUMB_INSET.toPx()
    drawRoundRect(color, topLeft = Offset(left, top), size = Size(width, length), cornerRadius = CornerRadius(width / 2f))
}
