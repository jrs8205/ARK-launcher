package org.arkikeskus.launcher.model

/**
 * What the app drawer paints between its content and what is behind it (the system wallpaper through the window, or
 * the user's own picture inside it), from the Settings > App drawer choices; see [drawerSurface].
 *
 * @property tint the user's colour the drawer's chips, fields and cards follow, or null for the theme's own.
 * @property paint the ARGB layer over what is behind: [tint], the theme's surface (null), or black over the picture.
 * @property opacity how much of [paint] shows, 0 = nothing of it.
 */
data class DrawerSurface(val tint: Int?, val paint: Int?, val opacity: Float)

private const val BLACK = 0xFF000000.toInt()

/**
 * The drawer's surface from its settings: a [color] (null = the theme's surface) at [colorOpacity]; the "Transparent"
 * choice ([LauncherSettings.DRAWER_COLOR_TRANSPARENT]) paints nothing, so only the (blurred) wallpaper shows; and
 * while the user's own picture is set ([hasPicture]) the colour is off altogether -- only the picture shows -- with a
 * black dim of [pictureDim] over it for legibility instead (the owner's 1.0.0 notes).
 */
fun drawerSurface(color: Int?, colorOpacity: Float, hasPicture: Boolean, pictureDim: Float): DrawerSurface = when {
    hasPicture -> DrawerSurface(tint = null, paint = BLACK, opacity = pictureDim.coerceIn(0f, 1f))
    color == LauncherSettings.DRAWER_COLOR_TRANSPARENT -> DrawerSurface(tint = null, paint = null, opacity = 0f)
    else -> DrawerSurface(tint = color, paint = color, opacity = colorOpacity.coerceIn(0f, 1f))
}
