package org.arkikeskus.launcher.feature.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The margin a widget keeps inside its cells when [alignWithIcons] is on (a Telegram request after 0.9.7):
 * the side margin an app icon of [iconSize] has inside a cell of [cellWidth], so the widget's edges line up
 * with the icons' instead of the cell edges -- what Launcher3's widget padding does. Off, none: the widget
 * fills its span edge to edge, as before.
 */
internal fun widgetInset(cellWidth: Dp, iconSize: Dp, alignWithIcons: Boolean): Dp =
    if (alignWithIcons) ((cellWidth - iconSize) / 2).coerceAtLeast(0.dp) else 0.dp

/** The size (one axis, dp) a widget is told about once [insetDp] is taken off both sides; never below 1. */
internal fun insetWidgetSizeDp(spanDp: Int, insetDp: Int): Int = (spanDp - 2 * insetDp).coerceAtLeast(1)
