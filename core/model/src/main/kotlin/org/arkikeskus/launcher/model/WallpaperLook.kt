package org.arkikeskus.launcher.model

/** The system wallpaper colour report, also kept on this device for the next start. */
data class WallpaperLook(val supportsDarkText: Boolean = false, val color: Int? = null) {
    /** The system bars treat a positive hint as white. Otherwise the glass tints the reported primary colour. */
    fun underGlass(tint: Float): Int = compositeOver(WHITE, tint, if (supportsDarkText) WHITE else color ?: BLACK)

    /** A black plate can only darken bare wallpaper. With glass, measure the tinted surface. */
    fun darkHomeText(glassTint: Float, plateOpacity: Float = 0f): Boolean =
        if (glassTint <= 0f && (!supportsDarkText || plateOpacity <= 0f)) supportsDarkText
        else contentIsDarkOn(BLACK, plateOpacity, underGlass(glassTint))

    private companion object {
        const val WHITE = -1
        const val BLACK = 0xff000000.toInt()
    }
}
