package org.arkikeskus.launcher.ui

import android.app.Application
import android.os.Process
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The long-press popup shows once its rows are known: the shortcuts and the Widgets row are asked
 * for before the card appears, so the card never grows, and jumps when anchored above an icon, as
 * they arrive a moment after it (issue #21). A probe that never answers does not keep the card
 * away for good: after [POPUP_ROWS_WAIT_MS] it shows with what has arrived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppActionPopupRowsWaitTest {
    @get:Rule val compose = createComposeRule()

    private val app = AppItem("com.example", "Main", Process.myUserHandle(), 0L, "Example")

    private fun show(widgets: CompletableDeferred<Boolean>) {
        compose.setContent {
            AppActionPopup(
                app = app,
                anchor = IntOffset(200, 200),
                preferAbove = false,
                actions = listOf(PopupAction("App info") {}),
                onDismiss = {},
                onWidgets = {},
                hasWidgets = { widgets.await() },
            )
        }
        compose.waitForIdle()
    }

    private fun cardShown() = compose.onAllNodesWithText("App info").fetchSemanticsNodes().isNotEmpty()

    @Test fun theCardWaitsForItsRowsAndShowsThemTogether() {
        val widgets = CompletableDeferred<Boolean>()
        show(widgets)

        compose.onNodeWithText("App info").assertDoesNotExist()

        widgets.complete(true)
        compose.waitUntil(5_000) { cardShown() }

        compose.onNodeWithText("Widgets").assertIsDisplayed()
        compose.onNodeWithText("App info").assertIsDisplayed()
    }

    /** BACK while the rows are still out has no popup window to land in: the menu must still go, for good. */
    @Test fun backWhileTheRowsAreStillOutDismissesTheMenuForGood() {
        val widgets = CompletableDeferred<Boolean>()
        var shown by mutableStateOf(true)
        var dismissed = 0
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            if (shown) {
                AppActionPopup(
                    app = app,
                    anchor = IntOffset(200, 200),
                    preferAbove = false,
                    actions = listOf(PopupAction("App info") {}),
                    onDismiss = { dismissed++; shown = false },
                    onWidgets = {},
                    hasWidgets = { widgets.await() },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertDoesNotExist()

        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        assertThat(dismissed).isEqualTo(1)

        widgets.complete(true)
        compose.mainClock.advanceTimeBy(POPUP_ROWS_WAIT_MS + 50)
        compose.waitForIdle()
        compose.onNodeWithText("App info").assertDoesNotExist()
    }

    @Test fun aProbeThatNeverAnswersStillLetsTheCardShowAfterTheWait() {
        show(CompletableDeferred())

        compose.onNodeWithText("App info").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(POPUP_ROWS_WAIT_MS + 50)
        compose.waitUntil(5_000) { cardShown() }

        compose.onNodeWithText("App info").assertIsDisplayed()
        compose.onNodeWithText("Widgets").assertDoesNotExist()
    }
}
