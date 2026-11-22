package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A full folder on a low window (a small phone, a split screen): the editor's buttons keep their room and
 * the grid of apps takes what is left, scrolling. The grid used to take its share of the window first, and
 * the buttons under it were cut.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h520dp")
class FolderCardLowWindowTest {
    @get:Rule val compose = createComposeRule()

    private val user = Process.myUserHandle()
    private val apps = (1..24).map { AppItem("com.app$it", "Main", user, 0L, "App $it") }
    private val folder = PlacedFolder(id = 7, name = "Tools", apps = apps, page = 0, cellX = 0, cellY = 0)

    private fun show(anchorBounds: Rect? = null) {
        compose.setContent {
            LauncherTheme {
                Box(Modifier.fillMaxSize()) {
                    FolderCard(
                        folder = folder,
                        badges = emptyMap(),
                        badgeShowCount = false,
                        badgeScale = 1f,
                        locked = false,
                        dragController = HomeDragController(),
                        onRename = {},
                        onAppClick = {},
                        onRenameApp = {},
                        onRemoveFromFolder = {},
                        onPinShortcut = {},
                        onReorder = { _, _ -> },
                        onDropOnHome = { _, _, _, _ -> },
                        onDropOnDock = { _, _ -> },
                        onDropOnRemove = {},
                        onDragOutEnd = {},
                        onDismiss = {},
                        anchorBounds = anchorBounds,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The height that shows of the node with [text], in dp: what is cut by its parents is not counted. */
    private fun shownHeightOf(text: String): Float {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        return node.boundsInRoot.height / compose.density.density
    }

    @Test fun theEditorsButtonsAreWholeUnderAFullFolder() {
        show()
        compose.onNodeWithContentDescription("Edit folder").performClick()
        compose.waitForIdle()

        // A text button is 40 dp tall; a few dp of one would be a sliver nobody can read or hit.
        for (label in listOf("Add apps", "Sort A–Z", "Delete folder")) {
            assertWithMessage("$label, shown height in dp").that(shownHeightOf(label)).isAtLeast(40f)
        }
    }

    @Test fun theCardStaysInsideTheWindow() {
        show()
        compose.onNodeWithContentDescription("Edit folder").performClick()
        compose.waitForIdle()

        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("folderCard").fetchSemanticsNode().boundsInRoot
        assertThat(card.top).isAtLeast(window.top)
        assertThat(card.bottom).isAtMost(window.bottom)
    }

    // --- Opened by its icon (1.2.1): the card has the room on the icon's side of the window ---------------------
    // A folder in the middle rows leaves about half the window on either side. The view fits there; the editor
    // (the name field and two lines of buttons, none of which scroll) does not, and it was cut to that room: no
    // app showed and "Delete folder" was a sliver. The same happens on a phone at a large display size.

    /** The folder's icon just above the middle of the 360 x 520 window: its card opens below it (222 dp of room). */
    private val upperMiddleIcon = Rect(left = 140f, top = 170f, right = 220f, bottom = 250f)

    /** Just below the middle: the card opens above it (222 dp of room). */
    private val lowerMiddleIcon = Rect(left = 140f, top = 270f, right = 220f, bottom = 350f)

    private fun shownHeightOfTag(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height / compose.density.density

    private fun placedCarets() = compose.onAllNodesWithTag("popup-caret", useUnmergedTree = true)
        .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }

    private fun theEditorIsWholeBy(icon: Rect) {
        show(icon)
        compose.onNodeWithContentDescription("Edit folder").performClick()
        compose.waitForIdle()

        for (label in listOf("Add apps", "Sort A–Z", "Delete folder")) {
            assertWithMessage("$label, shown height in dp").that(shownHeightOf(label)).isAtLeast(40f)
        }
        // A whole row of apps at least (an icon and its name); the folder scrolls for the rest.
        assertWithMessage("the grid, shown height in dp").that(shownHeightOfTag("folderGrid")).isAtLeast(90f)
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("folderCard").fetchSemanticsNode().boundsInRoot
        assertThat(card.top).isAtLeast(window.top)
        assertThat(card.bottom).isAtMost(window.bottom)
        // The card lies over the icon it opened from, so no caret points from it at anything.
        assertThat(placedCarets()).isEmpty()
    }

    @Test fun theEditorBelowAnIconInTheMiddleRowsKeepsItsButtonsAndARowOfApps() = theEditorIsWholeBy(upperMiddleIcon)

    @Test fun theEditorAboveAnIconInTheMiddleRowsKeepsItsButtonsAndARowOfApps() = theEditorIsWholeBy(lowerMiddleIcon)

    /** The view needs less (its title and a row of apps): it stays on its icon's side, with its caret. */
    @Test fun theViewByAnIconInTheMiddleRowsStillClearsItsIcon() {
        show(upperMiddleIcon)

        val card = compose.onNodeWithTag("folderCard").fetchSemanticsNode().boundsInRoot
        assertThat(card.top).isAtLeast(upperMiddleIcon.bottom)
        assertThat(placedCarets()).hasSize(1)
        assertWithMessage("the grid, shown height in dp").that(shownHeightOfTag("folderGrid")).isAtLeast(90f)
    }

    @Test fun theGridStillShowsAppsAndGivesWayOnlyAsMuchAsItMust() {
        show()
        compose.onNodeWithContentDescription("Edit folder").performClick()
        compose.waitForIdle()

        // The first row is there; the folder scrolls for the rest.
        compose.onNodeWithText("App 1").assertExists()
        with(compose.density) {
            assertThat(compose.onNodeWithText("App 1").fetchSemanticsNode().boundsInRoot.height).isGreaterThan(8.dp.toPx())
        }
    }
}
