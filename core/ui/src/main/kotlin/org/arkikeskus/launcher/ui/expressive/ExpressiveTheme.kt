package org.arkikeskus.launcher.ui.expressive

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Warm-orange accent shared by the settings page and the drawer search results. */
val Accent = Color(0xFFE2714A)

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
    /** The accent as a FOREGROUND on [surfaceHi] (titles, result text, icon tints): [Accent] itself on the system
     *  palettes, pushed toward the text on a chosen drawer colour whose cards it would vanish on (see the drawer's
     *  palette). Accent-filled surfaces (a selected tab, a button) keep [Accent] with their own white text. */
    val accent: Color = Accent,
)

val DarkExpressivePalette = ExpressivePalette(
    bg = Color(0xFF0C0E13), surfaceHi = Color(0xFF1D222B), text = Color(0xFFE9EBEE),
    dim = Color(0xFF9AA1AB), faint = Color(0xFF6C7079), trackOff = Color(0xFF3A404A),
    thumbOff = Color(0xFFAEB4BD), btn = Color.White.copy(alpha = 0.07f), shadow = 0.dp,
)

val LightExpressivePalette = ExpressivePalette(
    bg = Color(0xFFECEEF2), surfaceHi = Color(0xFFF4F6F9), text = Color(0xFF1A1C1E),
    dim = Color(0xFF5B616A), faint = Color(0xFF9499A1), trackOff = Color(0xFFC8CCD3),
    thumbOff = Color(0xFFFFFFFF), btn = Color.Black.copy(alpha = 0.05f), shadow = 1.dp,
)

val LocalExpressivePalette = staticCompositionLocalOf { LightExpressivePalette }

/** Provides the dark/light expressive palette by system theme. */
@Composable
fun ExpressiveTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkExpressivePalette else LightExpressivePalette
    CompositionLocalProvider(LocalExpressivePalette provides palette, content = content)
}
