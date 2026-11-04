package org.arkikeskus.launcher.ui

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import org.arkikeskus.launcher.model.LauncherSettings
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle

/** Android's condensed sans, by its system family name (every Android ships it). */
private val CondensedFontFamily = FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed")))

/** Android's casual hand-drawn face ("Coming Soon" on AOSP), by its system family name. */
private val CasualFontFamily = FontFamily(Font(DeviceFontFamilyName("casual")))

/**
 * The font family for a [LauncherSettings.fontFamily] key: the device families Android ships, so
 * nothing is bundled and the APK stays the size it is; an unknown key is the default sans.
 */
fun launcherFontFamily(key: String): FontFamily = when (key) {
    LauncherSettings.FONT_CONDENSED -> CondensedFontFamily
    LauncherSettings.FONT_SERIF -> FontFamily.Serif
    LauncherSettings.FONT_MONOSPACE -> FontFamily.Monospace
    LauncherSettings.FONT_CASUAL -> CasualFontFamily
    LauncherSettings.FONT_CURSIVE -> FontFamily.Cursive
    else -> FontFamily.Default
}

/**
 * [style] on top of the theme's text style. A Text given a style of its own replaces [LocalTextStyle] wholesale, so
 * the font the user chose (the theme's typography, [launcherFontFamily]) would fall back to the platform's default;
 * merged, the font stays and [style]'s own fields -- size, weight, shadow, platform options -- win. Every text the
 * launcher draws with a style of its own (the clock, the status bar, the built-in widgets) goes through this.
 */
@Composable
fun themedTextStyle(style: TextStyle): TextStyle = LocalTextStyle.current.merge(style)
