package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.arkikeskus.launcher.ui.component.NotificationBadge

/**
 * A home-screen folder: a rounded tile showing a 2×2 preview of the first four contained app icons,
 * an optional label, and a notification badge (aggregated from its contents).
 */
@Composable
fun FolderIcon(
    name: String,
    apps: List<AppItem>,
    showLabel: Boolean,
    badgeCount: Int,
    badgeShowCount: Boolean,
    badgeScale: Float = 1f,
    labelColor: Color = Color.White,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(15.dp))
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Preview icons scale with the tile so a shrunken folder still fits its 2×2 grid.
                val mini = size * (17f / 52f)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PreviewRow(apps.getOrNull(0), apps.getOrNull(1), mini)
                    PreviewRow(apps.getOrNull(2), apps.getOrNull(3), mini)
                }
            }
            NotificationBadge(count = badgeCount, showCount = badgeShowCount, scale = badgeScale)
        }
        if (showLabel) {
            val scale = LocalAppLabelScale.current
            Spacer(Modifier.height(4.dp))
            Text(
                text = name,
                color = labelColor,
                fontSize = (11f * scale).sp,
                lineHeight = (13f * scale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PreviewRow(left: AppItem?, right: AppItem?, size: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        PreviewSlot(left, size)
        PreviewSlot(right, size)
    }
}

@Composable
private fun PreviewSlot(app: AppItem?, size: Dp) {
    if (app == null) {
        Spacer(Modifier.size(size))
    } else {
        AppIcon(appItem = app, labelColor = Color.Transparent, showLabel = false, iconSize = size)
    }
}
