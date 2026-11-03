package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The test tag of the user's own picture behind the drawer. */
internal const val DRAWER_WALLPAPER_TAG = "drawer-wallpaper"

/** The blur of the user's own picture behind the drawer at 100 % strength: the same as the window
 *  blur of the system wallpaper (DrawerWallpaperBlur in the shell), so the two settings read alike. */
internal val DRAWER_PICTURE_BLUR = 24.dp

/** How much the user's own picture is blurred: nothing with the blur setting off, [DRAWER_PICTURE_BLUR]
 *  scaled by the strength setting with it on. Inside the window, so it works on every Android 12+. */
internal fun drawerWallpaperBlur(enabled: Boolean, strength: Float): Dp =
    if (enabled) DRAWER_PICTURE_BLUR * strength.coerceAtLeast(0f) else 0.dp
