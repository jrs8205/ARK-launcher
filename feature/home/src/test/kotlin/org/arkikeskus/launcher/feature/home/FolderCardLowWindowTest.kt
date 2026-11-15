package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun show() {
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
