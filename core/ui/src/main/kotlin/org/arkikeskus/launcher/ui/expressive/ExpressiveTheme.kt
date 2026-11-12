package org.arkikeskus.launcher.ui.expressive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.designsystem.theme.BasePalette
import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.designsystem.theme.currentBasePalette

/**
 * The colours of the hand-made surfaces (Settings, the app drawer, the long-press menus): a view of
 * the base palette ([toExpressive]) under the names those screens have always used.
 */
data class ExpressivePalette(
    val bg: Color,
    val surfaceHi: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val trackOff: Color,
    val thumbOff: Color,
    val btn: Color,
    val shadow: Dp,
    /** The accent as a FOREGROUND on [bg] and [surfaceHi] (titles, result text, icon tints): the fill where it reads
     *  as text, pushed toward the text where it does not (and again on a chosen drawer colour whose cards it would
     *  vanish on, see the drawer's palette). Filled surfaces take [fill] with [onFill] on it. */
    val accent: Color,
    /** A switch that is on, a filled button, the selected tab, an icon chip; and what is drawn on it. */
    val fill: Color,
    val onFill: Color,
    /** A search field, a tonal button, a menu item; and what is drawn on it. */
    val container: Color,
    val onContainer: Color,
)

/** The base palette under the hand-made surfaces' names. */
fun BasePalette.toExpressive(): ExpressivePalette = ExpressivePalette(
    bg = background,
    surfaceHi = card,
    text = text,
    dim = dim,
    faint = faint,
    trackOff = track,
    thumbOff = offThumb,
    btn = text.copy(alpha = if (dark) 0.07f else 0.05f),
    shadow = if (dark) 0.dp else 1.dp,
    accent = accentText,
    fill = fill,
    onFill = onFill,
    container = container,
    onContainer = onContainer,
)

/** The default colour's palettes, for what has no theme root of its own to read (tests, the drawer's
 *  text-family choice outside a theme). On screen the palette comes from the theme root. */
val DarkExpressivePalette: ExpressivePalette = basePalette(DefaultPaletteSeed, dark = true).toExpressive()
val LightExpressivePalette: ExpressivePalette = basePalette(DefaultPaletteSeed, dark = false).toExpressive()

val LocalExpressivePalette = staticCompositionLocalOf { LightExpressivePalette }

/** Provides the expressive palette of the theme root's base colour (the default colour in the
 *  phone's mode where there is no root above). */
@Composable
fun ExpressiveTheme(content: @Composable () -> Unit) {
    val base = currentBasePalette()
    val palette = remember(base) { base.toExpressive() }
    CompositionLocalProvider(LocalExpressivePalette provides palette, content = content)
}
