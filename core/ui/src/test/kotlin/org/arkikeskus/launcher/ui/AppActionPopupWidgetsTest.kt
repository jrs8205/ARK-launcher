package org.arkikeskus.launcher.ui

import android.app.Application
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The long-press popup's Widgets row: there for an app with home-screen widgets and a handler, as in Pixel Launcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppActionPopupWidgetsTest {
    @get:Rule val compose = createComposeRule()

    private val app = AppItem("com.example", "Main", Process.myUserHandle(), 0L, "Example")
    private var opened = 0
    private var dismissed = 0
    private var queried = 0

    private fun show(hasWidgets: Boolean, onWidgets: (() -> Unit)? = { opened++ }) {
        compose.setContent {
            AppActionPopup(
                app = app,
                anchor = IntOffset(200, 200),
                preferAbove = false,
                actions = listOf(PopupAction("App info") {}),
                onDismiss = { dismissed++ },
                onWidgets = onWidgets,
                hasWidgets = { queried++; hasWidgets },
            )
        }
        // The card shows once its shortcut query (off the main thread) has answered.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("App info").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun anAppWithWidgetsGetsAWidgetsRowAboveTheActions() {
        show(hasWidgets = true)

        compose.onNodeWithText("Widgets").assertIsDisplayed()
        compose.onNodeWithText("App info").assertIsDisplayed()
    }

    @Test fun tappingTheRowOpensTheWidgetsAndClosesThePopup() {
        show(hasWidgets = true)

        compose.onNodeWithText("Widgets").performClick()

        assertThat(opened).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun anAppWithoutWidgetsHasNoRow() {
        show(hasWidgets = false)

        compose.onNodeWithText("Widgets").assertDoesNotExist()
        compose.onNodeWithText("App info").assertIsDisplayed()
    }

    @Test fun withoutAHandlerThereIsNoRowAndNoQuery() {
        show(hasWidgets = true, onWidgets = null)

        compose.onNodeWithText("Widgets").assertDoesNotExist()
        assertThat(queried).isEqualTo(0)
    }
}
