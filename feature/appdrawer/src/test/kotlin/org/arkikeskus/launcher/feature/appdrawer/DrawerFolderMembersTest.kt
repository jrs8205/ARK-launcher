package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.LocalAppLabelLines
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The apps in a drawer folder's editor: three whole rows at most, scrolling for the rest. The grid's height
 * was a fixed 280 dp, which is three rows only at the default text size and with names on one line; with
 * larger text or names on two lines it ended in the middle of the third row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerFolderMembersTest {
    @get:Rule val compose = createComposeRule()

    private val user = Process.myUserHandle()
    private val apps = (1..30).map { i ->
        AppItem("com.app$i", "Main", user, 0L, if (i == 2) "A calendar with a very long name indeed" else "App $i")
    }

    private fun show(labelLines: Int, labelScale: Float) {
        compose.setContent {
            LauncherTheme {
                CompositionLocalProvider(LocalAppLabelLines provides labelLines, LocalAppLabelScale provides labelScale) {
                    Box(Modifier.size(328.dp, 600.dp)) {
                        DrawerFolderMembers(
                            apps = apps, columns = 4, badges = emptyMap(), badgeShowCount = false, badgeScale = 1f,
                            onAppClick = {}, onRemoveApp = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun cells(): List<SemanticsNode> =
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(DRAWER_FOLDER_MEMBERS_TAG))).fetchSemanticsNodes()

    /** Three rows show, each app whole (its icon AND its name), and the grid ends where the third row ends. */
    private fun assertThreeWholeRows() {
        val shown = cells().filter { it.boundsInRoot.height > 0f }
        for (cell in shown) {
            assertWithMessage("the part that shows of an app %s px tall", cell.size.height)
                .that(cell.boundsInRoot.height).isWithin(2f).of(cell.size.height.toFloat())
        }
        assertThat(shown).hasSize(12)
        val grid = compose.onNodeWithTag(DRAWER_FOLDER_MEMBERS_TAG).fetchSemanticsNode().boundsInRoot
        assertWithMessage("the grid's end against its last row's").that(grid.bottom).isWithin(2f).of(shown.maxOf { it.boundsInRoot.bottom })
    }

    @Test fun threeWholeRowsShowAtTheDefaultTextSize() {
        show(labelLines = 1, labelScale = 1f)

        assertThreeWholeRows()
    }

    @Test fun threeWholeRowsShowWithNamesOnTwoLines() {
        show(labelLines = 2, labelScale = 1f)

        assertThreeWholeRows()
    }

    @Test fun threeWholeRowsShowWithLargerNames() {
        show(labelLines = 1, labelScale = 1.3f)

        assertThreeWholeRows()
    }
}
