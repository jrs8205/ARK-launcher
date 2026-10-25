package org.arkikeskus.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Full-screen surfaces drawn over home inside the launcher's own window — the widget picker, the
 * first-run intro — tell the system bars' glyph colour what is behind the bars while they are shown
 * (issue #11): the home screen's wallpaper and ARK's own bar are hidden under them. The shell provides
 * one through [LocalSystemBarCovers]; a surface takes part with [CoverSystemBars].
 */
class SystemBarCovers {
    private val covers = mutableStateListOf<Cover>()

    /** Whether the most recently shown active cover is light; null while nothing covers home. */
    val coverIsLight: Boolean? get() = covers.lastOrNull { it.active }?.light

    internal class Cover(light: Boolean, active: Boolean) {
        var light by mutableStateOf(light)
        var active by mutableStateOf(active)
    }

    internal fun add(cover: Cover) {
        covers += cover
    }

    internal fun remove(cover: Cover) {
        covers -= cover
    }
}

val LocalSystemBarCovers = staticCompositionLocalOf<SystemBarCovers?> { null }

/**
 * While composed, the screen is covered behind both system bars by a surface that is [light] or dark;
 * [active] false while the surface is not drawn (the widget picker fades out during a widget drag).
 */
@Composable
fun CoverSystemBars(light: Boolean, active: Boolean = true) {
    val covers = LocalSystemBarCovers.current ?: return
    val cover = remember { SystemBarCovers.Cover(light, active) }
    SideEffect {
        cover.light = light
        cover.active = active
    }
    DisposableEffect(covers, cover) {
        covers.add(cover)
        onDispose { covers.remove(cover) }
    }
}
