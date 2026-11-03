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

/**
 * Whether the drawer's text reads best dark (black) rather than light (white): the drawer's [surface]
 * colour counts as much as its [opacity], and what shows through it ([behindLuminance]: the wallpaper
 * under the shell's dim, 0 when unknown) for the rest -- a yellow drawer at 10 % over a dark wallpaper
 * is a dark drawer, and takes white text.
 */
internal fun drawerTextIsDark(surface: Color, opacity: Float, behindLuminance: Float): Boolean {
    val o = opacity.coerceIn(0f, 1f)
    return surface.luminance() * o + behindLuminance.coerceIn(0f, 1f) * (1f - o) > LIGHT_BACKGROUND
}

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
 * The expressive palette (the search field, the folder cards, the popups) for the drawer: the
 * [system] light/dark palette when no [background] is chosen; otherwise the field and card
 * backgrounds tinted from the colour itself, with the light palette's text on a light colour and the
 * dark palette's on a dark one. These elements are opaque, so their own colour decides their text
 * whatever the drawer's opacity: a yellow pill on a 10 % yellow drawer over a dark wallpaper keeps
 * dark text while the app labels around it are white.
 */
internal fun drawerExpressivePalette(system: ExpressivePalette, background: Color?): ExpressivePalette {
    if (background == null) return system
    val textIsDark = drawerTextIsDark(background, opacity = 1f, behindLuminance = 0f)
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
internal fun DrawerExpressiveTheme(background: Color?, content: @Composable () -> Unit) {
    val system = if (isSystemInDarkTheme()) DarkExpressivePalette else LightExpressivePalette
    val palette = drawerExpressivePalette(system, background)
    CompositionLocalProvider(LocalExpressivePalette provides palette, content = content)
}
