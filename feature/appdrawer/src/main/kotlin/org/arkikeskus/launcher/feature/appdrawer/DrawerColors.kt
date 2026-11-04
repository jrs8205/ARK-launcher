package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.darkReadsBetter
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.shownLuminance

/** A colour scheme's onSurface darker than this (relative luminance) is dark text: schemes' text is near black or
 *  near white, so any mid value tells them apart. */
private const val LIGHT_BACKGROUND = 0.5f

/**
 * Whether the drawer's text reads best dark (black) rather than light (white): what the drawer shows -- its
 * [surface] colour at its [opacity] over what is behind it ([behindLuminance]: the wallpaper under the shell's dim or
 * the user's own picture, 0 when unknown), composited as the window composites it, in sRGB (gamma) space
 * ([shownLuminance]) -- takes the text that contrasts more with it ([darkReadsBetter], the WCAG contrast ratio): black
 * down to a relative luminance of 0.18 (L* 50, the perceptual mid grey), white below. A yellow drawer at 10 % over a
 * dark wallpaper is a dark drawer and takes white; orange #FFA726 (0.49) is a light one and takes black (10.8:1 where
 * white would be 1.9:1); a light theme's surface at 30 % over a dark wallpaper is the dark grey the screen shows (0.07),
 * not the linear mix of the luminances (0.29), and takes white. The system bars' glyphs over the drawer follow the
 * same rule (SystemBarContrast.kt in app), so labels and glyphs never disagree.
 */
internal fun drawerTextIsDark(surface: Color, opacity: Float, behindLuminance: Float): Boolean =
    darkReadsBetter(shownLuminance(surface, opacity, behindLuminance))

/** Whether a colour scheme's text is dark (its onSurface darker than mid grey). */
private fun ColorScheme.textIsDark(): Boolean = onSurface.luminance() < LIGHT_BACKGROUND

/**
 * The drawer's colour scheme with the user's [background] colour (Settings > App drawer, a Telegram
 * request) and its text dark or light by [textIsDark] ([drawerTextIsDark]): the theme's own [base]
 * when no colour is chosen and the theme's text already reads right; otherwise every surface slot the
 * drawer draws with takes the colour (or the theme's surface), its text black or white, its secondary
 * text and its chips and fields that text blended lightly into the colour, so the search field, the
 * Personal/Work tabs, the section headers and the folder cards all sit on the chosen colour instead of
 * the theme's. The user picks one colour; legibility follows by itself.
 */
internal fun drawerColorScheme(base: ColorScheme, background: Color?, textIsDark: Boolean): ColorScheme {
    if (background == null && base.textIsDark() == textIsDark) return base
    val surface = background ?: base.surface
    val onBackground = if (textIsDark) Color.Black else Color.White
    fun tint(alpha: Float) = onBackground.copy(alpha = alpha).compositeOver(surface)
    return base.copy(
        background = surface,
        onBackground = onBackground,
        surface = surface,
        onSurface = onBackground,
        surfaceVariant = tint(0.12f),
        onSurfaceVariant = tint(0.72f),
        surfaceContainerLowest = surface,
        surfaceContainerLow = tint(0.04f),
        surfaceContainer = tint(0.08f),
        surfaceContainerHigh = tint(0.12f),
        surfaceContainerHighest = tint(0.16f),
        outline = tint(0.5f),
        outlineVariant = tint(0.2f),
    )
}

/**
 * The scheme for the drawer's OPAQUE surfaces (the folder editor sheet, the add-apps dialog): they sit on their own
 * solid colour -- the user's [background] or the theme's surface -- so that colour alone decides their text, never
 * what shows through the see-through drawer around them (a light theme's sheet keeps dark text while the drawer's
 * labels, over a dark picture, have turned white).
 */
internal fun opaqueDrawerColorScheme(base: ColorScheme, background: Color?): ColorScheme =
    drawerColorScheme(base, background, drawerTextIsDark(background ?: base.surface, opacity = 1f, behindLuminance = 0f))

/** [opaqueDrawerColorScheme] for the drawer's own windows ([OpaqueDrawerSurface]); null outside the drawer. */
internal val LocalDrawerOpaqueColors = staticCompositionLocalOf<ColorScheme?> { null }

/** Wraps an opaque surface of the drawer in the scheme its own colour calls for ([LocalDrawerOpaqueColors]) instead of
 *  the see-through drawer's, whose text may have flipped for what shows through it. */
@Composable
internal fun OpaqueDrawerSurface(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LocalDrawerOpaqueColors.current ?: MaterialTheme.colorScheme, content = content)
}

/**
 * The expressive palette (the search field, the folder cards, the popups) for the drawer: the [system] light/dark
 * palette when no [background] is chosen; otherwise the drawer's colour with the text family its labels take
 * ([drawerTextIsDark]: the light palette's near-black text on a light colour, the dark palette's near-white on a dark
 * one -- one rule with the labels around them) and the card tone that carries that text: tinted toward the text as
 * Material's containers are, unless that would leave the text under [READABLE_TEXT] on a mid-light colour (red,
 * purple), then away from it -- which always clears it, as the text was chosen for the colour itself. The secondary
 * and faint text are the text blended into the FINAL card, not the base palette's greys made for near-white or
 * near-black cards (round 9: #5B616A read 1.4:1 on a darkened red card). These elements are opaque, so their own
 * colour decides whatever the drawer's opacity: a yellow pill on a 10 % yellow drawer over a dark wallpaper keeps
 * dark text while the app labels around it are white.
 */
internal fun drawerExpressivePalette(system: ExpressivePalette, background: Color?): ExpressivePalette {
    if (background == null) return system
    val textIsDark = drawerTextIsDark(background, opacity = 1f, behindLuminance = 0f)
    val onBackground = if (textIsDark) Color.Black else Color.White
    val base = if (textIsDark) LightExpressivePalette else DarkExpressivePalette
    val toward = onBackground.copy(alpha = CARD_TINT).compositeOver(background)
    val card = if (contrastRatio(base.text.luminance(), toward.luminance()) >= READABLE_TEXT) toward
    else (if (textIsDark) Color.White else Color.Black).copy(alpha = CARD_TINT).compositeOver(background)
    return base.copy(
        bg = background,
        surfaceHi = card,
        dim = base.text.copy(alpha = DIM_TEXT).compositeOver(card),
        faint = base.text.copy(alpha = FAINT_TEXT).compositeOver(card),
        btn = onBackground.copy(alpha = 0.07f),
    )
}

/** How much of the text colour (or its opposite) tints the drawer's colour into its cards and fields. */
private const val CARD_TINT = 0.12f

/** The WCAG ratio normal text needs (AA): a card whose tint leaves the text under it is tinted the other way. */
private const val READABLE_TEXT = 4.5f

/** The secondary (dim) and tertiary (faint) text as the text blended into the card: the light palette's own
 *  #5B616A on #F4F6F9 is the text at about 70 %. */
private const val DIM_TEXT = 0.76f
private const val FAINT_TEXT = 0.5f

/** [ExpressiveTheme][org.arkikeskus.launcher.ui.expressive.ExpressiveTheme] for the drawer, following
 *  the user's background colour ([drawerExpressivePalette]). */
@Composable
internal fun DrawerExpressiveTheme(background: Color?, content: @Composable () -> Unit) {
    val system = if (isSystemInDarkTheme()) DarkExpressivePalette else LightExpressivePalette
    val palette = drawerExpressivePalette(system, background)
    CompositionLocalProvider(LocalExpressivePalette provides palette, content = content)
}
