package org.arkikeskus.launcher.ui.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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
import org.arkikeskus.launcher.model.IconRequest

/**
 * Whether [AppIcon]s under this subtree render as Material You themed (monochrome) icons. Provided per
 * surface (home, dock, drawer) from the user's setting; default off. [AppIcon] feeds it (plus the
 * current dark/light theme) into the Coil request, so the cache keys per variant and toggling the
 * setting refreshes icons immediately.
 */
val LocalThemedIcons = compositionLocalOf { false }

/**
 * Package name of the selected third-party icon pack under this subtree ("" = none). Provided per
 * surface from the user's setting and fed into the Coil request (part of the cache key), so mapped apps
 * use the pack's icon and unmapped ones are masked to its style; overrides [LocalThemedIcons].
 */
val LocalIconPack = compositionLocalOf { "" }

/**
 * Size multiplier for [AppIcon] labels under this subtree (1.0 = the default 11sp). Provided per
 * surface (home, dock, drawer) from the user's setting so the label-size slider scales every app
 * label at once without threading the value through each composable's parameters. Default 1.0 keeps
 * standalone callers (settings previews, pickers) unchanged.
 */
val LocalAppLabelScale = compositionLocalOf { 1f }

/**
 * An app icon plus optional label. The icon is loaded via Coil (see AppIconFetcher), so it is
 * cached and loaded off the main thread. Caller supplies [labelColor] (white on wallpaper, the
 * theme on-surface color inside the drawer). When [badgeCount] > 0 a notification badge is drawn at
 * the icon's top-right corner ([badgeShowCount] picks number vs plain dot).
 */
@Composable
fun AppIcon(
    appItem: AppItem,
    labelColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 56.dp,
    showLabel: Boolean = true,
    maxLabelLines: Int = 1,
    badgeCount: Int = 0,
    badgeShowCount: Boolean = true,
    badgeScale: Float = 1f,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            AsyncImage(
                model = IconRequest(
                    appItem,
                    themed = LocalThemedIcons.current,
                    dark = isSystemInDarkTheme(),
                    iconPack = LocalIconPack.current,
                ),
                contentDescription = appItem.label,
                modifier = Modifier.size(iconSize),
            )
            NotificationBadge(count = badgeCount, showCount = badgeShowCount, scale = badgeScale)
        }
        if (showLabel) {
            val scale = LocalAppLabelScale.current
            Spacer(Modifier.height(4.dp))
            Text(
                text = appItem.label,
                color = labelColor,
                fontSize = (11f * scale).sp,
                lineHeight = (13f * scale).sp,
                maxLines = maxLabelLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Icon size that fits one grid cell: the surface's [preferred] size when the cell is roomy, shrunk
 * (an 8dp margin keeps the badge overhang and neighbours clear) when the user picks 6–7 columns on
 * a narrow screen — a fixed size bled into the neighbouring cells there. The floor keeps icons
 * recognisable and tappable on the most extreme combinations.
 */
fun iconSizeForCell(cellWidth: Dp, preferred: Dp): Dp =
    (cellWidth - 8.dp).coerceIn(32.dp, preferred)
