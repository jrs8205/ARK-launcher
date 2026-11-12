package org.arkikeskus.launcher.ui.expressive

import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.chameleonSeed
import org.arkikeskus.launcher.model.LauncherSettings

/**
 * The seed of the stored [baseColour] choice (one of [LauncherSettings.BASE_COLOURS]). The eight
 * hues are fixed; Black has none; the Chameleon takes its hue from [wallpaperArgb], the wallpaper's
 * main colour, and is monochrome while there is none. A choice this version does not know is the
 * default colour.
 */
fun paletteSeed(baseColour: String, wallpaperArgb: Int?): PaletteSeed = when (baseColour) {
    LauncherSettings.BASE_COLOUR_ORANGE -> PaletteSeed.Hue(15f)
    LauncherSettings.BASE_COLOUR_BLUE -> PaletteSeed.Hue(220f)
    LauncherSettings.BASE_COLOUR_GREEN -> PaletteSeed.Hue(143f, 0.85f)
    LauncherSettings.BASE_COLOUR_VIOLET -> PaletteSeed.Hue(275f)
    LauncherSettings.BASE_COLOUR_ROSE -> PaletteSeed.Hue(335f)
    LauncherSettings.BASE_COLOUR_TEAL -> PaletteSeed.Hue(182f)
    LauncherSettings.BASE_COLOUR_YELLOW -> PaletteSeed.Hue(46f, 1f)
    LauncherSettings.BASE_COLOUR_RED -> PaletteSeed.Hue(355f, 0.90f)
    LauncherSettings.BASE_COLOUR_BLACK -> PaletteSeed.Mono
    LauncherSettings.BASE_COLOUR_CHAMELEON -> chameleonSeed(wallpaperArgb)
    else -> DefaultPaletteSeed
}
