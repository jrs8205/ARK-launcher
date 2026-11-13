package org.arkikeskus.launcher.ui.expressive

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A grouped list: rows 2 dp apart, the group's outer corners 24 dp, the corners between its rows 6 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveGroupTest {
    @get:Rule val compose = createComposeRule()

    private val page = Color(0xFFFF0000)
    private val card = Color(0xFF00FF00)
    private val density = Density(1f)

    @Test fun aRowsPlaceInItsGroupGivesItsCorners() {
        assertThat(groupPosition(0, 1)).isEqualTo(GroupPosition.Only)
        assertThat(groupPosition(0, 2)).isEqualTo(GroupPosition.First)
        assertThat(groupPosition(1, 2)).isEqualTo(GroupPosition.Last)
        assertThat(groupPosition(1, 3)).isEqualTo(GroupPosition.Middle)
        assertThat(groupPosition(2, 3)).isEqualTo(GroupPosition.Last)

        fun corners(position: GroupPosition): List<Float> = groupShape(position).let {
            val size = Size(300f, 60f)
            listOf(it.topStart, it.topEnd, it.bottomEnd, it.bottomStart).map { c -> c.toPx(size, density) }
        }
        assertThat(corners(GroupPosition.Only)).containsExactly(24f, 24f, 24f, 24f).inOrder()
        assertThat(corners(GroupPosition.First)).containsExactly(24f, 24f, 6f, 6f).inOrder()
        assertThat(corners(GroupPosition.Middle)).containsExactly(6f, 6f, 6f, 6f).inOrder()
        assertThat(corners(GroupPosition.Last)).containsExactly(6f, 6f, 24f, 24f).inOrder()
    }

    private var showMiddle by mutableStateOf(true)

    private fun row(tag: String) = Modifier.expressiveGroupRow().testTag(tag).fillMaxWidth().height(60.dp).background(card)

    private fun showThreeRowsAHeadingAndOneMore() {
        compose.setContent {
            Box(Modifier.background(page)) {
                ExpressiveGroup(Modifier.width(300.dp).testTag("group")) {
                    Box(row("a"))
                    if (showMiddle) Box(row("b"))
                    Box(row("c"))
                    Box(Modifier.testTag("heading").fillMaxWidth().height(30.dp)) // not a row: ends the run
                    Box(row("d"))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    private fun pixels(tag: String): PixelMap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    /** A 24 dp corner leaves the point 3 px in from the corner outside the card; a 6 dp corner has it inside. */
    private fun PixelMap.wideCornerAt(top: Boolean): Boolean = this[3, if (top) 3 else height - 4] != card

    @Test fun rowsOfARunSitTwoDpApartAndARunEndsWithTheCardGap() {
        showThreeRowsAHeadingAndOneMore()
        assertThat(top("a")).isEqualTo(0.dp)
        assertThat(top("b")).isEqualTo(62.dp)
        assertThat(top("c")).isEqualTo(124.dp)
        assertThat(top("heading")).isEqualTo(194.dp) // 124 + 60 + the 10 dp after a run
        assertThat(top("d")).isEqualTo(224.dp)
        // The last row is followed by the gap a card on its own has.
        assertThat(compose.onNodeWithTag("group").getUnclippedBoundsInRoot().bottom).isEqualTo(294.dp)
    }

    @Test fun eachRowIsCutToItsPlaceInTheRun() {
        showThreeRowsAHeadingAndOneMore()
        val group = pixels("group")
        fun rowCorners(top: Int): Pair<Boolean, Boolean> =
            (group[3, top + 3] != card) to (group[3, top + 60 - 4] != card)
        assertWithMessage("first").that(rowCorners(0)).isEqualTo(true to false)
        assertWithMessage("middle").that(rowCorners(62)).isEqualTo(false to false)
        assertWithMessage("last").that(rowCorners(124)).isEqualTo(false to true)
        assertWithMessage("a row on its own").that(rowCorners(224)).isEqualTo(true to true)
        // The gap between two rows shows the page (under the rows' faint shadow), not a card.
        assertThat(group[150, 61].green).isLessThan(0.1f)
        assertThat(group[150, 61].red).isGreaterThan(0.9f)
    }

    @Test fun aRowThatIsNotShownLeavesTheOthersFirstAndLast() {
        showMiddle = false
        showThreeRowsAHeadingAndOneMore()
        assertThat(top("c")).isEqualTo(62.dp)
        val group = pixels("group")
        assertThat(group[3, 3] != card).isTrue()             // first: wide corner on top
        assertThat(group[3, 60 - 4] != card).isFalse()
        assertThat(group[3, 62 + 3] != card).isFalse()       // now the last: wide corner below
        assertThat(group[3, 62 + 60 - 4] != card).isTrue()
    }

    @Test fun aCardKnowsWhetherItIsARowOfAGroup() {
        var inRow: Triple<Boolean, Boolean, Boolean>? = null
        var alone: Triple<Boolean, Boolean, Boolean>? = null
        compose.setContent {
            alone = Triple(expressiveCardShape() == groupShape(GroupPosition.Only), expressiveCardGap() == 10.dp, true)
            androidx.compose.runtime.CompositionLocalProvider(LocalExpressiveGroupRow provides true) {
                inRow = Triple(
                    expressiveCardShape() == androidx.compose.ui.graphics.RectangleShape,
                    expressiveCardGap() == 0.dp,
                    expressiveCardShadow() == 0.dp,
                )
            }
        }
        compose.waitForIdle()
        assertThat(alone).isEqualTo(Triple(true, true, true))
        assertThat(inRow).isEqualTo(Triple(true, true, true))
    }
}
