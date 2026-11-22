package org.arkikeskus.launcher.ui

import android.app.Application
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #30 (a follow-up): a menu with many rows had to be scrolled. A menu whose rows do not fit its room at
 * their usual height (48 dp) draws them closer (44 dp, still a full touch target), so that the longest menu the
 * launcher has is on screen whole. A menu that fits keeps its usual rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // a row's height is its line of text: the real font's, not the stand-in's
class AppActionPopupDenseTest {
    @get:Rule val compose = createComposeRule()

    private val app = AppItem("com.example", "Main", Process.myUserHandle(), 0L, "Example")

    private fun show(rows: Int, wrapped: Int? = null, wrappedLabel: String = "Zum Startbildschirm hinzufügen") {
        compose.setContent {
            AppActionPopup(
                app = app,
                anchor = IntOffset(200, 40),
                preferAbove = false,
                // [wrapped] is the row whose label is too long for one line (German for "Add to home").
                actions = (1..rows).map { PopupAction(if (it == wrapped) wrappedLabel else "Row $it") {} },
                onDismiss = {},
                shortcutsOf = { emptyList() },
            )
        }
        // The menu is handed its shortcuts: nothing of it comes back from another thread, so there is no wait.
        compose.waitForIdle()
    }

    /** From one row's top to the next one's: the row's height and the 2 dp between the rows of a group. */
    private fun pitch() = top("Row 2") - top("Row 1")

    private fun top(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test fun aMenuThatFitsKeepsItsUsualRows() {
        show(rows = 9)

        assertThat(pitch()).isEqualTo(48.dp + 2.dp)
    }

    @Test fun aMenuTooTallForItsRoomDrawsItsRowsCloser() {
        show(rows = 11)

        assertThat(pitch()).isEqualTo(44.dp + 2.dp)
    }

    /** And with that it is on screen whole: the last row is not cut by the menu's edge, so nothing scrolls. */
    @Test fun theCloserRowsAreAllOnScreen() {
        show(rows = 11)

        compose.onNodeWithText("Row 11").assertIsDisplayed()
        val last = compose.onNodeWithText("Row 11")
        assertThat(last.getBoundsInRoot().height).isEqualTo(last.getUnclippedBoundsInRoot().height)
        assertThat(last.getUnclippedBoundsInRoot().height).isEqualTo(44.dp)
    }

    /**
     * Review round 2: ten rows fit their room at the usual height, ten rows of which one has its label on two
     * lines do not. Counted as one line each they stayed at the usual height and the last row was cut by the
     * menu's edge. The label's real height is counted: the rows are drawn closer and the last one is whole.
     */
    @Test fun aLabelOnTwoLinesIsCountedWithBothLines() {
        show(rows = 10, wrapped = 3)

        assertThat(pitch()).isEqualTo(44.dp + 2.dp)
        val last = compose.onNodeWithText("Row 10")
        last.assertIsDisplayed()
        assertThat(last.getBoundsInRoot().height).isEqualTo(last.getUnclippedBoundsInRoot().height)
    }

    @Test fun tenRowsOfOneLineKeepTheirUsualHeight() {
        show(rows = 10)

        assertThat(pitch()).isEqualTo(48.dp + 2.dp)
    }

    /**
     * Review round 3. In a window narrower than the menu (a split screen) the menu is as wide as the window,
     * and a label that fits one line in the full menu takes two. It was measured in the full width all the
     * same: ten rows were taken to fit and the last was cut. The label is measured in the width the menu has.
     */
    @Config(qualifiers = "en-w200dp-h891dp")
    @Test fun inANarrowWindowALabelIsMeasuredInTheWidthTheMenuHas() {
        show(rows = 10, wrapped = 3, wrappedLabel = "Add to the home screen")

        assertThat(pitch()).isEqualTo(44.dp + 2.dp)
        val last = compose.onNodeWithText("Row 10")
        assertThat(last.getBoundsInRoot().height).isEqualTo(last.getUnclippedBoundsInRoot().height)
    }

    /** The same label in a window with room for the whole menu fits its line, and ten rows keep their height. */
    @Test fun theSameLabelFitsOneLineInAWideWindow() {
        show(rows = 10, wrapped = 3, wrappedLabel = "Add to the home screen")

        assertThat(pitch()).isEqualTo(48.dp + 2.dp)
    }
}
