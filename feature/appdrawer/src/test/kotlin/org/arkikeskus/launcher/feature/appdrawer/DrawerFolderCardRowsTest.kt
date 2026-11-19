package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.FolderStyle
import org.arkikeskus.launcher.ui.component.LocalAppLabelLines
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A drawer folder with more apps than its card shows at once: the card shows whole rows under its title and
 * scrolls for the rest. The whole card used to scroll under a fixed height, which ended in the middle of a
 * row: icons without their names, or names cut through (a user's report, 2026-11-18).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerFolderCardRowsTest {
    @get:Rule val compose = createComposeRule()

    private val user = Process.myUserHandle()

    private fun folderOf(names: List<String>) =
        DrawerFolderUi(id = 1, name = "Tools", apps = names.mapIndexed { i, name -> AppItem("com.app$i", "Main", user, 0L, name) })

    private fun show(folder: DrawerFolderUi, labelLines: Int = 1) {
        compose.setContent {
            LauncherTheme {
                CompositionLocalProvider(LocalAppLabelLines provides labelLines) {
                    Box(Modifier.fillMaxSize()) {
                        DrawerFolderCard(
                            folder = folder,
                            bounds = Rect(left = 20f, top = 40f, right = 100f, bottom = 120f),
                            columns = 4,
                            style = FolderStyle(),
                            badges = emptyMap(),
                            badgeShowCount = false,
                            badgeScale = 1f,
                            onAppClick = {},
                            onAppLongClick = { _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The apps of the card as they are laid out: each an icon with its name, one node per app. */
    private fun cells(): List<SemanticsNode> =
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(DRAWER_FOLDER_CARD_TAG))).fetchSemanticsNodes()

    /** Every app that shows at all shows whole (its icon AND its name), and the list ends where its last row ends. */
    private fun assertWholeRows() {
        val shown = cells().filter { it.boundsInRoot.height > 0f }
        assertThat(shown).isNotEmpty()
        for (cell in shown) {
            assertWithMessage("the part that shows of an app %s px tall", cell.size.height)
                .that(cell.boundsInRoot.height).isWithin(2f).of(cell.size.height.toFloat())
        }
        val list = compose.onNodeWithTag(DRAWER_FOLDER_ROWS_TAG).fetchSemanticsNode().boundsInRoot
        assertWithMessage("the list's end against its last row's").that(list.bottom).isWithin(2f).of(shown.maxOf { it.boundsInRoot.bottom })
    }

    @Test fun aFullFolderShowsWholeRows() {
        show(folderOf((1..30).map { "App $it" }))

        assertWholeRows()
        // The title stays where it is: only the apps scroll.
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
    }

    @Test fun rowsWithNamesOnTwoLinesAreWholeToo() {
        // One long name among short ones: every row is as tall as a row whose names wrap.
        show(folderOf((1..30).map { if (it == 2) "A calendar with a very long name indeed" else "App $it" }), labelLines = 2)

        assertWholeRows()
    }

    @Test fun aSmallFolderIsAsTallAsItsApps() {
        show(folderOf(listOf("Mail", "Maps", "Notes")))

        assertWholeRows()
        assertThat(cells()).hasSize(3)
    }
}
