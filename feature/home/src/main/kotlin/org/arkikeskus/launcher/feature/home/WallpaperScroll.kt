package org.arkikeskus.launcher.feature.home

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
