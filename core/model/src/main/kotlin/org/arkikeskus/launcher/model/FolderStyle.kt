package org.arkikeskus.launcher.model

import kotlin.math.pow

/**
 * The look of an open folder (Settings > Folder appearance): app names under the icons, the icons'
 * size on top of the launcher's icon size, the room between the rows, and the card's colour
 * ([backgroundColor], ARGB; null = the theme's surface) at [backgroundOpacity].
 */
data class FolderStyle(
    val showLabels: Boolean = true,
    val iconScale: Float = 1f,
    val spacing: Float = 1f,
    val backgroundColor: Int? = null,
    val backgroundOpacity: Float = 1f,
)

/** The open folder's look as the settings have it. */
fun LauncherSettings.folderStyle(): FolderStyle = FolderStyle(
    showLabels = folderShowLabels,
    iconScale = folderIconScale,
    spacing = folderSpacing,
    backgroundColor = folderBackgroundColor,
    backgroundOpacity = folderBackgroundOpacity,
)

/** The relative luminance (0 = black, 1 = white) of an ARGB colour; its alpha is not part of it. */
fun argbLuminance(argb: Int): Float {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return (0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)).toFloat()
}

/**
 * Whether text on a card should be dark: the card's [colorLuminance] at its [opacity] over what is
 * behind it ([backdropLuminance]: a dimmed home screen or a wallpaper under a veil, dark by default)
 * is light enough for it. A see-through light card over a dark backdrop is a dark card.
 */
fun contentIsDarkOn(colorLuminance: Float, opacity: Float, backdropLuminance: Float = 0.1f): Boolean {
    val a = opacity.coerceIn(0f, 1f)
    return a * colorLuminance + (1f - a) * backdropLuminance > 0.4f
}
