package org.arkikeskus.launcher.ui.expressive

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Grouped lists, the shape of the hand-made surfaces: the rows of a group sit 2 dp apart, the group's outer corners
 * are wide and the corners between its rows narrow. A row's place in its group is only known once it is clear which
 * rows are shown, so the group gives each row its shape when it places it: no row has to know its neighbours.
 */

/** Where a row sits among the rows of its group. */
enum class GroupPosition { Only, First, Middle, Last }

/** The place of row [index] among [count] rows. */
fun groupPosition(index: Int, count: Int): GroupPosition = when {
    count <= 1 -> GroupPosition.Only
    index == 0 -> GroupPosition.First
    index == count - 1 -> GroupPosition.Last
    else -> GroupPosition.Middle
}

/** A group's outer corners, and a card's on its own. */
private val OUTER_CORNER = 24.dp

/** The corners between the rows of a group. */
private val INNER_CORNER = 6.dp

/** The room between two rows of a group. */
private val ROW_GAP = 2.dp

/** The room under a group's last row, and under a card on its own. */
private val END_GAP = 10.dp

/** A row's card by its [position]: outer corners 24 dp, corners between rows 6 dp. */
fun groupShape(position: GroupPosition): RoundedCornerShape {
    val top = if (position == GroupPosition.Only || position == GroupPosition.First) OUTER_CORNER else INNER_CORNER
    val bottom = if (position == GroupPosition.Only || position == GroupPosition.Last) OUTER_CORNER else INNER_CORNER
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/** Search fields, buttons, the selected tab. */
val PillShape: Shape = RoundedCornerShape(50)

/** True under an [ExpressiveGroup]: what marks itself as a row there ([expressiveGroupRow]) is shaped by the group. */
val LocalInExpressiveGroup = staticCompositionLocalOf { false }

/** True for the content of a row the group shapes: its cards are plain rectangles without a gap of their own. */
val LocalExpressiveGroupRow = staticCompositionLocalOf { false }

/** A card's shape: on its own the wide corners all round; as a group's row a rectangle the group cuts to shape. */
@Composable
@ReadOnlyComposable
fun expressiveCardShape(): Shape = if (LocalExpressiveGroupRow.current) RectangleShape else groupShape(GroupPosition.Only)

/** The room under a card: its own on its own; none as a group's row (the group spaces its rows). */
@Composable
@ReadOnlyComposable
fun expressiveCardGap(): Dp = if (LocalExpressiveGroupRow.current) 0.dp else END_GAP

/** A card's shadow: the palette's on its own; none as a group's row (the group casts it along the row's shape). */
@Composable
@ReadOnlyComposable
fun expressiveCardShadow(): Dp = if (LocalExpressiveGroupRow.current) 0.dp else LocalExpressivePalette.current.shadow

private class GroupRowData(val overlay: (DrawScope.(outline: Outline) -> Unit)?) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@GroupRowData

    // The same overlay is the same row data: a row that recomposes does not make its group lay itself out again.
    override fun equals(other: Any?): Boolean = other is GroupRowData && other.overlay === overlay
    override fun hashCode(): Int = System.identityHashCode(overlay)
}

/**
 * Marks a direct child of an [ExpressiveGroup] as one of its rows. [overlay] is drawn over the row, in the row's own
 * coordinates, with the outline the group gave it (a mark around the row's card).
 */
fun Modifier.expressiveGroupRow(overlay: (DrawScope.(outline: Outline) -> Unit)? = null): Modifier =
    then(GroupRowData(overlay))

/** A row with an overlay as the group last placed it. */
private class PlacedRow(
    val top: Int,
    val width: Int,
    val height: Int,
    val shape: Shape,
    val overlay: DrawScope.(Outline) -> Unit,
)

/**
 * A column whose rows form grouped lists. The children marked with [expressiveGroupRow] that follow one another
 * are one group: 2 dp apart, each cut to its place in the group ([groupShape]), with the palette's shadow along
 * that shape, and the room a card has under it after the last. Any other child that takes room (a heading, a
 * preview) is placed as a plain column would place it and ends the group before it. A child that takes no room
 * (a row whose content is not shown) counts for nothing.
 */
@Composable
fun ExpressiveGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shadow = LocalExpressivePalette.current.shadow
    val overlays = remember { mutableListOf<PlacedRow>() }
    Layout(
        content = { CompositionLocalProvider(LocalInExpressiveGroup provides true, content = content) },
        modifier = modifier.drawWithContent {
            drawContent()
            overlays.forEach { row ->
                val outline = row.shape.createOutline(Size(row.width.toFloat(), row.height.toFloat()), layoutDirection, this)
                translate(top = row.top.toFloat()) { row.overlay(this, outline) }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val rows = measurables.map { it.parentData as? GroupRowData }

        // Each shown row's place in its run; null for what is not a row.
        val positions = arrayOfNulls<GroupPosition>(placeables.size)
        val run = ArrayList<Int>()
        fun endRun() {
            run.forEachIndexed { n, index -> positions[index] = groupPosition(n, run.size) }
            run.clear()
        }
        placeables.forEachIndexed { index, placeable ->
            if (placeable.height == 0) return@forEachIndexed
            if (rows[index] != null) run += index else endRun()
        }
        endRun()

        val rowGap = ROW_GAP.roundToPx()
        val endGap = END_GAP.roundToPx()
        val tops = IntArray(placeables.size)
        var y = 0
        var previous: GroupPosition? = null
        var any = false
        placeables.forEachIndexed { index, placeable ->
            if (placeable.height > 0) {
                if (any) {
                    y += when (previous) {
                        null -> 0 // after a plain child: it brings its own room
                        GroupPosition.First, GroupPosition.Middle -> rowGap
                        GroupPosition.Last, GroupPosition.Only -> endGap
                    }
                }
                previous = positions[index]
                any = true
            }
            tops[index] = y
            y += placeable.height
        }
        if (any && previous != null) y += endGap

        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        val shadowPx = shadow.toPx()
        layout(width.coerceAtLeast(constraints.minWidth), y.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            overlays.clear()
            placeables.forEachIndexed { index, placeable ->
                val position = positions[index]
                if (position == null) {
                    placeable.place(0, tops[index])
                } else {
                    val shape = groupShape(position)
                    placeable.placeWithLayer(0, tops[index]) {
                        this.shape = shape
                        clip = true
                        shadowElevation = shadowPx
                    }
                    rows[index]?.overlay?.let { overlays += PlacedRow(tops[index], placeable.width, placeable.height, shape, it) }
                }
            }
        }
    }
}
