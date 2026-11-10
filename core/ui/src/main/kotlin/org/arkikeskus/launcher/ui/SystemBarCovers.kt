package org.arkikeskus.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Full-screen surfaces drawn over home inside the launcher's own window — the widget picker, the
 * first-run intro — tell the system bars' glyph colour what is behind the bars while they are shown
 * (issue #11): the home screen's wallpaper and ARK's own bar are hidden under them. The shell provides
 * one through [LocalSystemBarCovers]; a surface takes part with [CoverSystemBars]. A surface that only
 * darkens home edge to edge -- the open folder's scrim -- takes part with [DimSystemBars] instead.
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

    private val dims = mutableStateListOf<Dim>()

    /**
     * How dark the scrims home's own surfaces draw over the whole of it make home behind the bars, as they
     * are now (0 = none): several stack as their layers do. Not a cover: home still shows through, darker.
     */
    val homeDim: Float get() = 1f - dims.fold(1f) { rest, dim -> rest * (1f - dim.alpha().coerceIn(0f, 1f)) }

    internal class Dim(val alpha: () -> Float)

    internal fun add(dim: Dim) {
        dims += dim
    }

    internal fun remove(dim: Dim) {
        dims -= dim
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

/**
 * While composed, a black scrim at [alpha] lies over the whole of home, behind both system bars (the open
 * folder's). [alpha] is read as it is now, in whatever reads [SystemBarCovers.homeDim]: a scrim that fades
 * is followed without recomposing.
 */
@Composable
fun DimSystemBars(alpha: () -> Float) {
    val covers = LocalSystemBarCovers.current ?: return
    val current by rememberUpdatedState(alpha)
    val dim = remember { SystemBarCovers.Dim { current() } }
    DisposableEffect(covers, dim) {
        covers.add(dim)
        onDispose { covers.remove(dim) }
    }
}
