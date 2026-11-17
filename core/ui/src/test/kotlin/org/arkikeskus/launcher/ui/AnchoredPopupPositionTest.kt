package org.arkikeskus.launcher.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where an anchored card goes: the long-press menus and the folder cards (a window of their own or in place). */
class AnchoredPopupPositionTest {
    private val window = IntSize(1000, 2000)
    private val card = IntSize(400, 300)

    @Test
    fun `below the anchor it is centred on it, a margin under it`() {
        val at = anchoredPopupPosition(IntOffset(500, 600), preferAbove = false, windowSize = window, contentSize = card)

        assertThat(at).isEqualTo(IntOffset(300, 624))
    }

    @Test
    fun `above the anchor its bottom is a margin over it`() {
        val at = anchoredPopupPosition(IntOffset(500, 1400), preferAbove = true, windowSize = window, contentSize = card)

        assertThat(at).isEqualTo(IntOffset(300, 1400 - 300 - 24))
    }

    @Test
    fun `it stays a margin inside the window's sides`() {
        assertThat(anchoredPopupPosition(IntOffset(40, 600), false, window, card).x).isEqualTo(24)
        assertThat(anchoredPopupPosition(IntOffset(990, 600), false, window, card).x).isEqualTo(1000 - 400 - 24)
    }

    @Test
    fun `it stays inside the window's top and bottom`() {
        assertThat(anchoredPopupPosition(IntOffset(500, 1900), false, window, card).y).isEqualTo(2000 - 300 - 24)
        assertThat(anchoredPopupPosition(IntOffset(500, 100), true, window, card).y).isEqualTo(24)
    }

    @Test
    fun `it keeps out of the system bars it is told of`() {
        // A tall card above its anchor would reach the status bar: it stops a margin under it.
        val tall = IntSize(400, 1300)
        assertThat(anchoredPopupPosition(IntOffset(500, 1200), true, window, tall, safeTop = 100, safeBottom = 60).y).isEqualTo(124)
        // Below an anchor near the bottom it stops a margin over the navigation bar.
        assertThat(anchoredPopupPosition(IntOffset(500, 1900), false, window, card, safeTop = 100, safeBottom = 60).y)
            .isEqualTo(2000 - 60 - 300 - 24)
    }
}
