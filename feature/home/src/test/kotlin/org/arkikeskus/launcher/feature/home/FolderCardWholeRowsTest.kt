package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import org.robolectric.annotation.GraphicsMode

/**
 * A folder with more apps than its card shows at once: the card shows whole rows and scrolls for the rest.
 * The grid's height was a share of the window, or what the card had left by its icon, and it ended in the
 * middle of a row: icons without their names, or names cut through (a user's report, 2026-11-18).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h520dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderCardWholeRowsTest {
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

    /** The apps of the grid as they are laid out: each an icon with its name, one node per app. */
    private fun cells(): List<SemanticsNode> =
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("folderGrid"))).fetchSemanticsNodes()

    /** Every app that shows at all shows whole (its icon AND its name), and the grid ends where its last row ends. */
    private fun assertWholeRows() {
        val grid = compose.onNodeWithTag("folderGrid").fetchSemanticsNode().boundsInRoot
        val shown = cells().filter { it.boundsInRoot.height > 0f }
        assertThat(shown).isNotEmpty()
        for (cell in shown) {
            assertWithMessage("the part that shows of an app %s px tall", cell.size.height)
                .that(cell.boundsInRoot.height).isWithin(2f).of(cell.size.height.toFloat())
        }
        assertWithMessage("the grid's end against its last row's").that(grid.bottom).isWithin(2f).of(shown.maxOf { it.boundsInRoot.bottom })
    }

    @Test fun aFullFolderShowsWholeRows() {
        show()

        assertWholeRows()
    }

    /** The folder's icon just above the middle of the 360 x 520 window: its card has the 222 dp below it. */
    private val upperMiddleIcon = Rect(left = 140f, top = 170f, right = 220f, bottom = 250f)

    @Test fun aFolderOpenedByAnIconInTheMiddleRowsShowsWholeRows() {
        show(upperMiddleIcon)

        assertWholeRows()
    }

    @Test fun theEditorOfAFullFolderShowsWholeRows() {
        show()
        compose.onNodeWithContentDescription("Edit folder").performClick()
        compose.waitForIdle()

        assertWholeRows()
    }
}
