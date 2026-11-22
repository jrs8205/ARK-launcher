package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.LocalBackgroundDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The menu of an app on the home screen or in the dock. Unlocked it has the four rows it always had. On a
 * locked desktop (issue #30, a follow-up) it still opens, with the rows that change nothing there: the app's
 * page in Android's settings and its uninstall. Renaming and removing are the lock's to refuse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeAppMenuTest {
    @get:Rule val compose = createComposeRule()

    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private var renamed = 0
    private var removed = 0
    private var dismissed = 0

    private fun show(locked: Boolean, inDock: Boolean = false) {
        compose.setContent {
            // The menu asks Android for the app's shortcuts and widgets before it shows (the shadow answers).
            // Asked on another thread, the answer comes back on that thread under the rule the tests had then, and the menu's
            // rows were then put on screen from it: "Only the original thread that created a view hierarchy
            // can touch its views", once in a whole-task run. Here it asks at once, on this thread.
            CompositionLocalProvider(LocalBackgroundDispatcher provides Dispatchers.Unconfined) {
                HomeAppMenu(
                    app = mail,
                    anchor = IntOffset(200, 200),
                    preferAbove = false,
                    inDock = inDock,
                    locked = locked,
                    held = false,
                    onRename = { renamed++ },
                    onRemove = { removed++ },
                    onPinShortcut = {},
                    onWidgets = {},
                    onDismiss = { dismissed++ },
                )
            }
        }
        // The card shows once its shortcut query (off the main thread) has answered.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("App info").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun anUnlockedHomeAppHasItsFourRows() {
        show(locked = false)

        compose.onNodeWithText("App info").assertIsDisplayed()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("Remove from home").assertIsDisplayed()
        compose.onNodeWithText("Uninstall").assertIsDisplayed()

        compose.onNodeWithText("Remove from home").performClick()
        compose.waitForIdle()
        assertThat(removed).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun aDockAppsRemoveRowNamesTheDock() {
        show(locked = false, inDock = true)

        compose.onNodeWithText("Remove from dock").assertIsDisplayed()
        compose.onNodeWithText("Remove from home").assertDoesNotExist()
    }

    @Test fun aLockedDesktopKeepsAppInfoAndUninstall() {
        show(locked = true)

        compose.onNodeWithText("App info").assertIsDisplayed()
        compose.onNodeWithText("Uninstall").assertIsDisplayed()
    }

    @Test fun aLockedDesktopOffersNoRenameAndNoRemove() {
        show(locked = true)

        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Remove from home").assertDoesNotExist()
    }

    @Test fun aLockedDockOffersNoRemoveEither() {
        show(locked = true, inDock = true)

        compose.onNodeWithText("App info").assertIsDisplayed()
        compose.onNodeWithText("Remove from dock").assertDoesNotExist()
    }
}
