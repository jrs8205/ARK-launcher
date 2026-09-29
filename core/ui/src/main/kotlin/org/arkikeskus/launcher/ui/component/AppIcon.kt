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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.IconEpochs
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
 * Icon re-fetch tokens (see [IconEpochs]), provided once at the shell root. [AppIcon] folds the
 * app's epoch into the Coil request so a package update re-renders that icon immediately — even
 * when it is already composed (dock, open home page), where only a model change re-launches the load.
 */
val LocalIconEpochs = compositionLocalOf { IconEpochs() }

/**
 * Size multiplier for [AppIcon] labels under this subtree (1.0 = the default 11sp). Provided per
 * surface (home, dock, drawer) from the user's setting so the label-size slider scales every app
 * label at once without threading the value through each composable's parameters. Default 1.0 keeps
 * standalone callers (settings previews, pickers) unchanged.
 */
val LocalAppLabelScale = compositionLocalOf { 1f }

/**
 * How many lines an [AppIcon] / [AppLabel] label may wrap onto under this subtree (1 or 2). Provided
 * per surface from the user's two-line switches — home (apps, folders, shortcuts) and drawer have
 * their own — so every label on a surface follows one setting. Default 1 keeps standalone callers
 * (settings previews, pickers) unchanged; the dock passes 1 explicitly to stay a compact bar.
 */
val LocalAppLabelLines = compositionLocalOf { 1 }

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
    maxLabelLines: Int = LocalAppLabelLines.current,
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
                    epoch = LocalIconEpochs.current.of(appItem.packageName),
                ),
                contentDescription = appItem.label,
                modifier = Modifier.size(iconSize),
            )
            NotificationBadge(count = badgeCount, showCount = badgeShowCount, scale = badgeScale)
        }
        if (showLabel) AppLabel(appItem.label, labelColor, maxLabelLines)
    }
}

/**
 * Icon size that fits one grid cell: the surface's [preferred] size when the cell is roomy, shrunk
 * (an 8dp margin keeps the badge overhang and neighbours clear) when the user picks 6–7 columns on
 * a narrow screen — a fixed size bled into the neighbouring cells there. A fixed-height cell passes
 * [cellHeight] and its [labelBlock] too: 7–8 rows on an enlarged display size leave so little
 * height that a width-sized icon squeezed the label to a few dp and cut it off.
 *
 * Two floors: 32dp against a narrow cell, but only 24dp against a short one. The largest settings
 * the launcher allows (label slider 160 %, system font at its 130 % cap) need 31dp of label in a
 * 60dp cell, so a rigid 32dp icon cut the text again. The whole cell stays the touch target, so the
 * smaller icon costs nothing in tappability; below 24dp the icon stops being recognisable and the
 * label gives way instead.
 */
fun iconSizeForCell(
    cellWidth: Dp,
    preferred: Dp,
    cellHeight: Dp = Dp.Unspecified,
    labelBlock: Dp = 0.dp,
): Dp {
    val byWidth = (cellWidth - 8.dp).coerceAtLeast(32.dp)
    val byHeight = if (cellHeight.isSpecified) (cellHeight - labelBlock - 4.dp).coerceAtLeast(24.dp) else byWidth
    return min(byWidth, byHeight).coerceAtMost(preferred)
}

/** The most the system font size may grow a grid label (see [labelFontFactor]). */
const val MAX_LABEL_FONT_SCALE = 1.3f

/**
 * The system font scale as applied to grid labels: honoured up to [MAX_LABEL_FONT_SCALE]. Home and
 * dock cells are fixed-size, so an unbounded accessibility scale either grew the row over its
 * neighbours or (with the old dp height cap) sliced the glyphs in half; a bounded one keeps a whole
 * line readable. The user's own label-size slider is separate and applies in full.
 */
fun labelFontFactor(fontScale: Float): Float = fontScale.coerceAtMost(MAX_LABEL_FONT_SCALE)

/** Gap between an icon and its label. */
private val LABEL_GAP = 4.dp

/** Height of one label line in dp — the text's line height and its clip box agree on this. */
private fun labelLineHeight(labelScale: Float, fontFactor: Float): Dp = (13f * labelScale * fontFactor).dp

/** Vertical room [AppLabel] takes under an icon (gap + [lines] lines); zero when labels are off. */
fun labelBlockHeight(showLabel: Boolean, labelScale: Float, fontFactor: Float, lines: Int = 1): Dp =
    if (showLabel) LABEL_GAP + labelLineHeight(labelScale, fontFactor) * lines else 0.dp

/**
 * The label under an app / folder / shortcut icon (emits the gap and the text into the caller's
 * Column). Sized in dp-derived sp so the rendered line is [labelLineHeight] whatever the system
 * font scale, which is what [labelBlockHeight] budgets for; [maxLines] alone bounds the height.
 *
 * No dp height cap on the text: the old `heightIn(max = line × maxLines)` was meant to match the
 * budget, but Android rounds each text line to whole pixels, so two lines came out a pixel taller
 * than the fractional cap and Compose ellipsized the label to ONE line — the drawer's two-line
 * labels never wrapped. The at-most-one-pixel-per-line overshoot is invisible in a centred cell.
 */
@Composable
fun AppLabel(text: String, color: Color, maxLines: Int = LocalAppLabelLines.current) {
    val scale = LocalAppLabelScale.current
    val density = LocalDensity.current
    val factor = labelFontFactor(density.fontScale)
    val line = labelLineHeight(scale, factor)
    Spacer(Modifier.height(LABEL_GAP))
    Text(
        text = text,
        color = color,
        fontSize = with(density) { (11f * scale * factor).dp.toSp() },
        lineHeight = with(density) { line.toSp() },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}
