package org.arkikeskus.launcher.feature.home

import android.app.WallpaperManager
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

/*
 * The system wallpaper scrolls with the home pager (Settings > Home > Wallpaper scrolling). Where it stands
 * is a function of the pager's continuous position alone, so every way of turning a page moves it, and the
 * wrap-around needs no animation of its own: on the swipe from the last page to the copy of page 0 the
 * picture runs back to where page 0 has it.
 */

/** A static picture is spread over at least this many pages, as in AOSP Launcher3: with two or three pages
 *  it moves a third of its spare width per page instead of racing across it. */
private const val MIN_PARALLAX_PAGES = 4

/**
 * The page turns the wallpaper's whole width is spread over. A live wallpaper draws its own pages and gets
 * the true count; one page still counts as one turn (nothing to divide by otherwise).
 */
internal fun wallpaperSpan(pageCount: Int, liveWallpaper: Boolean): Int =
    if (liveWallpaper) (pageCount - 1).coerceAtLeast(1) else pageCount.coerceAtLeast(MIN_PARALLAX_PAGES) - 1

/**
 * The wallpaper's horizontal offset, 0 (its start) to 1 (its end), for the pager's continuous [position]:
 * pages 0 until [pageCount], then the tail page. On the real pages it grows by one [wallpaperSpan]th per
 * page. On the way to the tail it runs back to 0 when the tail shows the copy of page 0
 * ([tailShowsPageZero]); when the tail is the empty page a drag can drop onto, it runs on to where that
 * page will stand once it is a real one, so the drop changes nothing on screen.
 */
internal fun wallpaperOffsetX(
    position: Float,
    pageCount: Int,
    tailShowsPageZero: Boolean,
    liveWallpaper: Boolean,
): Float {
    val last = (pageCount - 1).coerceAtLeast(0)
    val span = wallpaperSpan(pageCount, liveWallpaper)
    val x = if (position <= last) {
        position.coerceAtLeast(0f) / span
    } else {
        val atLast = last.toFloat() / span
        val atTail = if (tailShowsPageZero) 0f else pageCount.toFloat() / wallpaperSpan(pageCount + 1, liveWallpaper)
        atLast + (atTail - atLast) * (position - last).coerceAtMost(1f)
    }
    return x.coerceIn(0f, 1f)
}

/** Where the offsets go: the system wallpaper, through the home window. A test hands a recording one. */
internal interface WallpaperOffsets {
    /** Whether the system wallpaper is a live one (asked when home comes to the front). */
    fun isLive(): Boolean

    /** The wallpaper stands at [x] (0..1); [xStep] is how far one page moves it, a hint live wallpapers use. */
    fun set(x: Float, xStep: Float)

    /** Back to the system's own place for it, as before the launcher said anything. */
    fun clear()
}

/** The real [WallpaperOffsets] of the window this composition is in. */
@Composable
internal fun rememberWallpaperOffsets(): WallpaperOffsets {
    val view = LocalView.current
    return remember(view) { WindowWallpaperOffsets(view) }
}

/** Nothing is sent while the view has no window; a window that is going away throws, which is swallowed. */
private class WindowWallpaperOffsets(private val view: View) : WallpaperOffsets {
    private val manager: WallpaperManager? = runCatching { WallpaperManager.getInstance(view.context) }.getOrNull()
    private var sentStep = Float.NaN

    override fun isLive(): Boolean = runCatching { manager?.wallpaperInfo != null }.getOrDefault(false)

    override fun set(x: Float, xStep: Float) {
        val token = view.windowToken ?: return
        runCatching {
            if (xStep != sentStep) {
                manager?.setWallpaperOffsetSteps(xStep, 1f)
                sentStep = xStep
            }
            manager?.setWallpaperOffsets(token, x, WALLPAPER_Y)
        }
    }

    override fun clear() {
        val token = view.windowToken ?: return
        runCatching {
            // Withdrawing the request alone leaves the picture where it was last put (the system keeps the last
            // offset any window asked for): it is first put where the system starts it, the right end on a
            // right-to-left phone and the left end otherwise.
            val rtl = view.context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            manager?.setWallpaperOffsets(token, if (rtl) 1f else 0f, WALLPAPER_Y)
            manager?.clearWallpaperOffsets(token)
        }
        sentStep = Float.NaN
    }
}

/** The picture never moves up or down: the middle, as the system has it. */
private const val WALLPAPER_Y = 0.5f

/** How long a change that is not a scroll takes to settle. */
private const val SETTLE_MS = 250

/** The offset last handed to the wallpaper (null when it stands where the system put it), and whether it has
 *  been handed back to the system since the scrolling was turned off. */
private class LastSent {
    var x: Float? = null
    var cleared = false
}

/**
 * Keeps the system wallpaper where [wallpaperOffsetX] says for [pagerState], frame by frame, while [enabled] is
 * true. False returns it to the system's own place once; null (the stored choice is not read yet) leaves it alone.
 *
 * The value is read in a snapshotFlow, so a page turn recomposes nothing. A scroll moves the picture directly. A
 * change that is not a scroll (the page count, a live wallpaper replacing a picture, the tail turning from the copy
 * of page 0 into the empty new page mid-swipe) would make it step: the difference to the value last sent is carried
 * in a correction that runs to zero in [SETTLE_MS] instead. Each return to the front asks again whether the
 * wallpaper is a live one and sends the current value again: another window may have moved it meanwhile.
 */
@Composable
internal fun WallpaperScrollEffect(
    pagerState: PagerState,
    pageCount: Int,
    tailShowsPageZero: Boolean,
    enabled: Boolean?,
    offsets: WallpaperOffsets = rememberWallpaperOffsets(),
) {
    var live by remember(offsets) { mutableStateOf(offsets.isLive()) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(offsets) {
        live = offsets.isLive()
        resumes++
        onPauseOrDispose { }
    }
    val correction = remember { Animatable(0f) }
    val sent = remember(offsets) { LastSent() }
    LaunchedEffect(pagerState, offsets, enabled, pageCount, tailShowsPageZero, live, resumes) {
        if (enabled != true) {
            // Turned off: back to the system's place, once (a return to the front does not repeat it). Not known
            // yet: nothing at all.
            if (enabled == false && !sent.cleared) {
                offsets.clear()
                sent.cleared = true
            }
            sent.x = null
            correction.snapTo(0f)
            return@LaunchedEffect
        }
        sent.cleared = false
        fun target(): Float = wallpaperOffsetX(
            position = pagerState.currentPage + pagerState.currentPageOffsetFraction,
            pageCount = pageCount,
            tailShowsPageZero = tailShowsPageZero,
            liveWallpaper = live,
        )
        val step = 1f / wallpaperSpan(pageCount, live)
        correction.snapTo(sent.x?.let { it - target() } ?: 0f)
        launch { correction.animateTo(0f, tween(SETTLE_MS)) }
        snapshotFlow { (target() + correction.value).coerceIn(0f, 1f) }.collect { x ->
            offsets.set(x, step)
            sent.x = x
        }
    }
}
