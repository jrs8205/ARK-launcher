package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where the wallpaper stands for a position of the home pager (pages 0 until pageCount, then the tail page). */
class WallpaperOffsetTest {
    private val tolerance = 1e-4f

    private fun x(position: Float, pages: Int, copyAtTail: Boolean = false, live: Boolean = false) =
        wallpaperOffsetX(position, pages, tailShowsPageZero = copyAtTail, liveWallpaper = live)

    @Test fun aStaticPictureSpreadsOverAtLeastFourPages() {
        assertThat(wallpaperSpan(pageCount = 3, liveWallpaper = false)).isEqualTo(3)
        assertThat(x(0f, 3)).isWithin(tolerance).of(0f)
        assertThat(x(1f, 3)).isWithin(tolerance).of(1f / 3)
        assertThat(x(2f, 3)).isWithin(tolerance).of(2f / 3)

        assertThat(wallpaperSpan(pageCount = 6, liveWallpaper = false)).isEqualTo(5)
        assertThat(x(0f, 6)).isWithin(tolerance).of(0f)
        assertThat(x(2.5f, 6)).isWithin(tolerance).of(0.5f)
        assertThat(x(5f, 6)).isWithin(tolerance).of(1f)
    }

    @Test fun aLiveWallpaperGetsTheTruePageCount() {
        assertThat(wallpaperSpan(pageCount = 3, liveWallpaper = true)).isEqualTo(2)
        assertThat(wallpaperSpan(pageCount = 1, liveWallpaper = true)).isEqualTo(1)
        assertThat(x(0f, 3, live = true)).isWithin(tolerance).of(0f)
        assertThat(x(1f, 3, live = true)).isWithin(tolerance).of(0.5f)
        assertThat(x(2f, 3, live = true)).isWithin(tolerance).of(1f)
    }

    @Test fun onePageStaysAtTheStart() {
        assertThat(x(0f, 1)).isEqualTo(0f)
        assertThat(x(0f, 1, live = true)).isEqualTo(0f)
    }

    @Test fun theWrapRunsBackToTheStart() {
        assertThat(x(2f, 3, copyAtTail = true)).isWithin(tolerance).of(2f / 3)
        assertThat(x(2.5f, 3, copyAtTail = true)).isWithin(tolerance).of(1f / 3)
        assertThat(x(3f, 3, copyAtTail = true)).isWithin(tolerance).of(0f)

        for (pages in 2..8) for (live in listOf(false, true)) {
            val last = pages - 1
            // Continuous where the tail segment begins, and the copy of page 0 stands where page 0 does.
            assertThat(x(last.toFloat(), pages, copyAtTail = true, live = live))
                .isWithin(tolerance).of(x(last.toFloat(), pages, copyAtTail = false, live = live))
            assertThat(x(pages.toFloat(), pages, copyAtTail = true, live = live))
                .isWithin(tolerance).of(x(0f, pages, copyAtTail = true, live = live))
        }
    }

    @Test fun theNewPageEndsWhereItWillBeOnceReal() {
        for (pages in 1..8) for (live in listOf(false, true)) {
            assertThat(x(pages.toFloat(), pages, live = live))
                .isWithin(tolerance).of(x(pages.toFloat(), pages + 1, live = live))
        }
        // Three pages of a static picture: the new fourth page fills the span.
        assertThat(x(3f, 3)).isWithin(tolerance).of(1f)
        assertThat(x(2.5f, 3)).isWithin(tolerance).of((2f / 3 + 1f) / 2)
    }

    @Test fun theValueIsAlwaysWithinZeroAndOne() {
        for (pages in 1..8) for (copy in listOf(false, true)) for (live in listOf(false, true)) {
            var position = -0.5f
            while (position <= pages + 0.5f) {
                val value = x(position, pages, copyAtTail = copy, live = live)
                assertThat(value).isAtLeast(0f)
                assertThat(value).isAtMost(1f)
                position += 0.1f
            }
        }
    }
}
