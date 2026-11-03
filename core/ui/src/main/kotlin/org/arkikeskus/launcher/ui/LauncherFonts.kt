package org.arkikeskus.launcher.ui

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import org.arkikeskus.launcher.model.LauncherSettings

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
