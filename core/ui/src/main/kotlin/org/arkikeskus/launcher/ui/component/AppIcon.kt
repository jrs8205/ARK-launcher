package org.arkikeskus.launcher.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import coil3.compose.AsyncImage
import org.arkikeskus.launcher.model.AppItem

/**
 * An app icon plus optional label. The icon is loaded via Coil (see AppIconFetcher), so it is
 * cached and loaded off the main thread. Caller supplies [labelColor] (white on wallpaper, the
 * theme on-surface color inside the drawer).
 */
@Composable
fun AppIcon(
    appItem: AppItem,
    labelColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 56.dp,
    showLabel: Boolean = true,
    maxLabelLines: Int = 1,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = appItem,
            contentDescription = appItem.label,
            modifier = Modifier.size(iconSize),
        )
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = appItem.label,
                color = labelColor,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = maxLabelLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
