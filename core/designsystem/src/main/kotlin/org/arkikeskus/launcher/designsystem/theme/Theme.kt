package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/** The base colour of a launcher nobody has coloured yet: orange. */
val DefaultPaletteSeed: PaletteSeed = PaletteSeed.Hue(15f)

/** The palette the theme root provides; null outside one (a popup or a preview on its own). Read it
 *  through [currentBasePalette]. */
val LocalBasePalette = staticCompositionLocalOf<BasePalette?> { null }

/** The palette of the theme root above, or the default colour in the phone's mode where there is none. */
@Composable
@ReadOnlyComposable
fun currentBasePalette(): BasePalette =
    LocalBasePalette.current ?: basePalette(DefaultPaletteSeed, isSystemInDarkTheme())

/**
 * The launcher theme: every colour comes from [palette], the one the user's base colour gives.
 * Material's own components read it as a colour scheme ([toColorScheme]); the hand-made ones read
 * it through [LocalBasePalette].
 */
@Composable
fun LauncherTheme(
    palette: BasePalette = basePalette(DefaultPaletteSeed, isSystemInDarkTheme()),
    /** The user's font for every text the launcher draws itself ([withFontFamily]). */
    fontFamily: FontFamily = FontFamily.Default,
    content: @Composable () -> Unit,
) {
    val launcherColors = if (palette.dark) DarkLauncherColors else LightLauncherColors
    val colorScheme = remember(palette) { palette.toColorScheme() }

    CompositionLocalProvider(
        LocalLauncherColors provides launcherColors,
        LocalBasePalette provides palette,
    ) {
        val typography = remember(fontFamily) { LauncherTypography.withFontFamily(fontFamily) }
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
