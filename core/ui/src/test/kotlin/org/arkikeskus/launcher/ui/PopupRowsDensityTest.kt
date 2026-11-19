package org.arkikeskus.launcher.ui

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When a long-press menu draws its rows closer (issue #30, a follow-up): only when the rows at their usual
 * height are taller than the menu's room. A row is its one line of text and its padding, 48 dp at least; a
 * compact one 44 dp at least. Rows of a group are 2 dp apart and two groups 10 dp.
 */
class PopupRowsDensityTest {
    private val line = 24.dp // bodyLarge at the standard font size

    @Test fun rowsThatFitTheirRoomKeepTheirUsualHeight() {
        // The home screen's fullest menu: four shortcuts, then Widgets and four rows.
        assertThat(popupRowsHeight(List(9) { line }, groups = 2, compact = false)).isEqualTo(456.dp)
        assertThat(popupRowsCompact(List(9) { line }, groups = 2, room = 520.dp)).isFalse()
    }

    @Test fun rowsTallerThanTheirRoomAreDrawnCloser() {
        // The app drawer's fullest menu: four shortcuts, then Widgets and six rows.
        assertThat(popupRowsHeight(List(11) { line }, groups = 2, compact = false)).isEqualTo(556.dp)
        assertThat(popupRowsCompact(List(11) { line }, groups = 2, room = 520.dp)).isTrue()
    }

    @Test fun theFullestMenuFitsItsRoomOnceItsRowsAreCloser() {
        assertThat(popupRowsHeight(List(11) { line }, groups = 2, compact = true)).isEqualTo(512.dp)
        assertThat(popupRowsHeight(List(11) { line }, groups = 2, compact = true)).isAtMost(520.dp)
    }

    @Test fun rowsExactlyAsTallAsTheirRoomStayAsTheyAre() {
        assertThat(popupRowsCompact(List(9) { line }, groups = 2, room = 456.dp)).isFalse()
        assertThat(popupRowsCompact(List(9) { line }, groups = 2, room = 455.dp)).isTrue()
    }

    /** A low window gives less room than the menu's own cap: fewer rows are then already too many. */
    @Test fun aLowWindowDrawsFewerRowsCloser() {
        assertThat(popupRowsCompact(List(6) { line }, groups = 1, room = 280.dp)).isTrue()
    }

    /** Larger text makes a row taller than its least height, usual or compact: the padding is what shrinks. */
    @Test fun aLineOfLargerTextKeepsItsPadding() {
        assertThat(popupRowHeight(text = 32.dp, compact = false)).isEqualTo(56.dp)
        assertThat(popupRowHeight(text = 32.dp, compact = true)).isEqualTo(52.dp)
    }

    @Test fun aMenuWithoutRowsHasNoHeight() {
        assertThat(popupRowsHeight(List(0) { line }, groups = 0, compact = false)).isEqualTo(0.dp)
    }

    /**
     * Review round 2: a label that wraps makes its row a line taller. Counted as one line each, ten rows with
     * one such label (four shortcuts and the drawer's rows in German, "Zum Startbildschirm hinzufügen") were
     * taken to fit, stayed at their usual height, and the last row was cut. Each row counts with its own text.
     */
    @Test fun aLabelOnTwoLinesCountsWithBothOfThem() {
        val oneWrapped = List(9) { line } + (line * 2)

        assertThat(popupRowsHeight(List(10) { line }, groups = 2, compact = false)).isEqualTo(506.dp)
        assertThat(popupRowsHeight(oneWrapped, groups = 2, compact = false)).isEqualTo(530.dp)
        assertThat(popupRowsCompact(oneWrapped, groups = 2, room = 520.dp)).isTrue()
        assertThat(popupRowsHeight(oneWrapped, groups = 2, compact = true)).isAtMost(520.dp)
    }
}
