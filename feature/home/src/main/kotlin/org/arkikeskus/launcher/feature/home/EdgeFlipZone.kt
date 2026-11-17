package org.arkikeskus.launcher.feature.home

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** How near an edge of the grid the finger must hold a dragged item for the page to flip. It was 20 dp, which
 *  asked for the finger pressed against the bezel: a reach few thumbs have on the far side (issue #30). */
private val EDGE_FLIP_ZONE = 32.dp

/** The zone's share of a column at most: on a dense grid an icon held over the outermost column, on its way
 *  into a cell there, must not count as held at the edge. */
private const val EDGE_FLIP_ZONE_MAX_CELL_SHARE = 0.4f

/** The edge zone in pixels for a grid whose columns are [cellWidthPx] wide. */
internal fun edgeFlipZonePx(cellWidthPx: Float, density: Density): Float =
    minOf(with(density) { EDGE_FLIP_ZONE.toPx() }, cellWidthPx * EDGE_FLIP_ZONE_MAX_CELL_SHARE)
