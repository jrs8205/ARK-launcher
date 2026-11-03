package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** A background lighter than this takes dark text; the rest light (relative luminance, mid grey). */
private const val LIGHT_BACKGROUND = 0.5f

/** Whether text over [background] reads best dark (black) rather than light (white). */
internal fun drawerTextIsDark(background: Color): Boolean = background.luminance() > LIGHT_BACKGROUND

/**
 * The drawer's colour scheme with the user's [background] colour (Settings > App drawer, a Telegram
 * request): the theme's own [base] when none is chosen; otherwise every surface slot the drawer draws
 * with takes the colour, its text black or white by the colour's lightness ([drawerTextIsDark]), its
 * secondary text and its chips and fields that text blended lightly into the colour, so the search
 * field, the Personal/Work tabs, the section headers and the folder cards all sit on the chosen
 * colour instead of the theme's. The user picks one colour; legibility follows by itself.
 */
internal fun drawerColorScheme(base: ColorScheme, background: Color?): ColorScheme {
    if (background == null) return base
    val onBackground = if (drawerTextIsDark(background)) Color.Black else Color.White
    fun tint(alpha: Float) = onBackground.copy(alpha = alpha).compositeOver(background)
    return base.copy(
        background = background,
        onBackground = onBackground,
        surface = background,
        onSurface = onBackground,
        surfaceVariant = tint(0.12f),
        onSurfaceVariant = tint(0.72f),
        surfaceContainerLowest = background,
        surfaceContainerLow = tint(0.04f),
        surfaceContainer = tint(0.08f),
        surfaceContainerHigh = tint(0.12f),
        surfaceContainerHighest = tint(0.16f),
        outline = tint(0.5f),
        outlineVariant = tint(0.2f),
    )
}

/**
 * The expressive palette (the search field, the folder cards, the popups) for the drawer: the
 * [system] light/dark palette when no [background] is chosen; otherwise the light palette's text on a
 * light colour and the dark palette's on a dark one, with the field and card backgrounds tinted from
 * the colour itself, so the search pill no longer sits as a dark slab on a yellow drawer.
 */
internal fun drawerExpressivePalette(system: ExpressivePalette, background: Color?): ExpressivePalette {
    if (background == null) return system
    val textIsDark = drawerTextIsDark(background)
    val onBackground = if (textIsDark) Color.Black else Color.White
    val base = if (textIsDark) LightExpressivePalette else DarkExpressivePalette
    return base.copy(
        bg = background,
        surfaceHi = onBackground.copy(alpha = 0.12f).compositeOver(background),
        btn = onBackground.copy(alpha = 0.07f),
    )
}

/** [ExpressiveTheme][org.arkikeskus.launcher.ui.expressive.ExpressiveTheme] for the drawer, following
 *  the user's background colour ([drawerExpressivePalette]). */
@Composable
internal fun DrawerExpressiveTheme(backgroundColor: Int?, content: @Composable () -> Unit) {
    val system = if (isSystemInDarkTheme()) DarkExpressivePalette else LightExpressivePalette
    val palette = drawerExpressivePalette(system, backgroundColor?.let { Color(it) })
    CompositionLocalProvider(LocalExpressivePalette provides palette, content = content)
}
