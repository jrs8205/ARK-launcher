package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The floating copy of a lifted folder, drawn by the host above every home surface (the grid's own
 * copy was covered by the dock, composed after it, right at the drop zone). Placed from [finger],
 * the pointer in the host's coordinates, read inside the offset so a move only replaces the copy.
 *
 * The icon rides centred under the finger; its label, when the tile it left shows one, hangs below
 * the icon in its own room, [tileWidth] wide like the grid cell. A copy boxed to the icon's height
 * alone cut a home folder's name to nothing while the names were on.
 */
@Composable
internal fun LiftedFolderImage(
    folder: PlacedFolder,
    finger: () -> Offset,
    iconScale: Float,
    tileWidth: Dp,
    showLabel: Boolean,
    showName: Boolean,
    labelColor: Color,
) {
    val iconSize = 52.dp * iconScale
    val width = tileWidth.coerceAtLeast(iconSize)
    val density = LocalDensity.current
    val halfIconPx = with(density) { (iconSize / 2).toPx() }
    val halfWidthPx = with(density) { (width / 2).toPx() }
    Box(
        modifier = Modifier
            .offset {
                val at = finger()
                IntOffset((at.x - halfWidthPx).roundToInt(), (at.y - halfIconPx).roundToInt())
            }
            .width(width)
            .graphicsLayer {
                alpha = 0.92f
                scaleX = 1.1f
                scaleY = 1.1f
                // Lifted about the icon's centre, the point under the finger, not the box's.
                transformOrigin = TransformOrigin(0.5f, if (size.height > 0f) halfIconPx / size.height else 0.5f)
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        FolderIcon(
            name = folder.name,
            apps = folder.apps,
            showLabel = showLabel,
            showName = showName,
            labelColor = labelColor,
            badgeCount = 0,
            badgeShowCount = false,
            size = iconSize,
            customIcon = folder.icon,
        )
    }
}
