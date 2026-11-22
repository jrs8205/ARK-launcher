package org.arkikeskus.launcher.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.ui.expressive.ExpressiveGroupBreak
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.toExpressive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** A long-press menu is a grouped list: rows in the container colour, shaped by their place, 48 dp at least. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PopupGroupedRowsTest {
    @get:Rule val compose = createComposeRule()

    private val palette = basePalette(DefaultPaletteSeed, dark = false).toExpressive()
    private val page = Color(0xFF0000FF)

    private fun near(a: Color, b: Color) =
        abs(a.red - b.red) < 0.02f && abs(a.green - b.green) < 0.02f && abs(a.blue - b.blue) < 0.02f

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides palette) {
                Box(Modifier.background(page).testTag("menu")) {
                    PopupMenuContent(anchor = IntOffset(200, 0), preferAbove = false, windowWidthPx = 1000f, showCaret = false) {
                        ShortcutPopupRow(label = "New tab", shortLabel = "Tab", icon = null, onClick = {}, onPin = {})
                        ExpressiveGroupBreak()
                        PopupMenuRow("App info", LauncherIcons.Info) {}
                        PopupMenuRow("Edit", LauncherIcons.Edit) {}
                        PopupMenuRow("Uninstall", null) {}
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot()

    @Test fun everyRowIsAtLeastFortyEightDpHigh() {
        show()
        // Rows of one text line: the next row's top tells this row's height.
        val appInfo = bounds("App info").top
        val edit = bounds("Edit").top
        val uninstall = bounds("Uninstall").top
        assertThat(edit - appInfo).isAtLeast(48.dp + 2.dp)
        assertThat(uninstall - edit).isAtLeast(48.dp + 2.dp)
    }

    @Test fun theRowsAreAGroupInTheContainerColourAndTheShortcutsAGroupOfTheirOwn() {
        show()
        val menu = compose.onNodeWithTag("menu").captureToImage().toPixelMap()
        fun colourAt(x: Int, y: Int) = menu[x, y]
        // The shortcut row is a group of one: wide corners above and below, the container colour inside.
        assertWithMessage("shortcut row inside").that(near(colourAt(8, 24), palette.container)).isTrue()
        assertWithMessage("shortcut row corner").that(near(colourAt(3, 3), palette.container)).isFalse()
        // After it the break: the room a group has under it shows what is behind the menu.
        val firstAction = with(compose.density) {
            (bounds("App info").top - compose.onNodeWithTag("menu").getUnclippedBoundsInRoot().top).roundToPx()
        }
        // The first action row starts a new group: a wide corner at its top, a narrow one at its bottom.
        val rowTop = firstAction // the node found by its text is the whole row (its semantics are merged)
        assertWithMessage("first action corner").that(near(colourAt(3, rowTop + 3), palette.container)).isFalse()
        assertWithMessage("first action inside").that(near(colourAt(8, rowTop + 24), palette.container)).isTrue()
        assertWithMessage("gap above the actions").that(near(colourAt(60, rowTop - 5), palette.container)).isFalse()
    }
}
