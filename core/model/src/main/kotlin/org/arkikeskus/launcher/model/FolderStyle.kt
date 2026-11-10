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

/** The WCAG contrast ratio (1..21) between two relative luminances. */
fun contrastRatio(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

/** The opaque ARGB colour seen when [color] is painted at [opacity] over [backdrop] (their alphas ignored). */
fun compositeOver(color: Int, opacity: Float, backdrop: Int): Int {
    val a = opacity.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val top = (color shr shift) and 0xFF
        val under = (backdrop shr shift) and 0xFF
        return (top * a + under * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/**
 * Whether text on a card should be dark: black has at least the contrast white has with what the card
 * looks like, its [color] at its [opacity] over what is behind it ([backdrop]). A see-through card
 * goes by what shows through it.
 */
fun contentIsDarkOn(color: Int, opacity: Float, backdrop: Int): Boolean {
    val seen = argbLuminance(compositeOver(color, opacity, backdrop))
    return contrastRatio(seen, 0f) >= contrastRatio(seen, 1f)
}
