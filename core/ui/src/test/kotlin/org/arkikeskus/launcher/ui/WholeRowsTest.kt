package org.arkikeskus.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A scrolling list of equal rows shows whole rows only: where the room it has ends inside a row, it takes
 * the rows that fit and leaves the rest of the room. A folder's apps were cut between an icon and its name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class WholeRowsTest {
    @get:Rule val compose = createComposeRule()

    /** [rows] rows of 100 dp in a scrolling list that is given [room] at most; the height the list takes, in dp. */
    private fun heightIn(room: Dp, rows: Int): Float {
        compose.setContent {
            Box(Modifier.size(100.dp, room)) {
                Column(Modifier.testTag("list").wholeRows { 100.dp.roundToPx() }.verticalScroll(rememberScrollState())) {
                    repeat(rows) { Box(Modifier.size(100.dp)) }
                }
            }
        }
        compose.waitForIdle()
        return compose.onNodeWithTag("list").fetchSemanticsNode().size.height / compose.density.density
    }

    @Test fun aRoomThatEndsInsideARowShowsTheRowsThatFitWhole() {
        assertThat(heightIn(room = 250.dp, rows = 5)).isEqualTo(200f)
    }

    @Test fun aRoomOfWholeRowsIsUsedToItsEnd() {
        assertThat(heightIn(room = 300.dp, rows = 5)).isEqualTo(300f)
    }

    @Test fun rowsThatAllFitTakeTheirOwnHeight() {
        assertThat(heightIn(room = 350.dp, rows = 2)).isEqualTo(200f)
    }

    @Test fun aRoomSmallerThanOneRowIsUsedAsItIs() {
        // Part of a row is more than nothing: the list still scrolls.
        assertThat(heightIn(room = 80.dp, rows = 5)).isEqualTo(80f)
    }

    @Test fun aListWithoutALimitIsNotLimited() {
        compose.setContent {
            // The outer list gives its content all the height it asks for.
            Column(Modifier.height(250.dp).verticalScroll(rememberScrollState())) {
                Column(Modifier.testTag("list").wholeRows { 100.dp.roundToPx() }) {
                    repeat(5) { Box(Modifier.size(100.dp)) }
                }
            }
        }
        compose.waitForIdle()

        assertThat(compose.onNodeWithTag("list").fetchSemanticsNode().size.height / compose.density.density).isEqualTo(500f)
    }

    /** [rows] rows of [row] in a scrolling list of [maxRows] rows at most with all the room it could want; the
     *  height the list takes, in pixels. */
    private fun pixelsOf(row: Dp, rows: Int, maxRows: Int): Int {
        compose.setContent {
            Box(Modifier.size(100.dp, 2000.dp)) {
                Column(
                    Modifier.testTag("list").wholeRows(maxRows) { row.roundToPx() }.verticalScroll(rememberScrollState()),
                ) {
                    repeat(rows) { Box(Modifier.size(100.dp, row)) }
                }
            }
        }
        compose.waitForIdle()
        return compose.onNodeWithTag("list").fetchSemanticsNode().size.height
    }

    @Test fun noMoreRowsShowThanTheMostItMayShow() {
        assertThat(pixelsOf(row = 100.dp, rows = 10, maxRows = 3)).isEqualTo(300)
    }

    @Test fun fewerRowsThanTheMostTakeTheirOwnHeight() {
        assertThat(pixelsOf(row = 100.dp, rows = 2, maxRows = 3)).isEqualTo(200)
    }

    /**
     * At a density of 1.5 a row of 100.4 dp is 151 px, and three of them 453. The most was once given as a
     * height in dp (three times the row = 301.2 dp = 452 px), a pixel short of the third row, and the list of
     * whole rows then showed two. Counted in rows of pixels, three rows are three rows at every density.
     */
    @Config(qualifiers = "en-hdpi")
    @Test fun threeRowsAreThreeRowsAtAFractionalDensity() {
        assertThat(compose.density.density).isEqualTo(1.5f)

        assertThat(pixelsOf(row = 100.4.dp, rows = 10, maxRows = 3)).isEqualTo(3 * 151)
    }
}
