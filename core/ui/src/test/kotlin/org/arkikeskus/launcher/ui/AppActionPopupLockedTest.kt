package org.arkikeskus.launcher.ui

import android.app.Application
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import android.os.UserHandle
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntOffset
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps

/** A LauncherApps that answers the popup's own query (published shortcuts, not the pinned ones), which the
 *  stock shadow refuses: every query gets the one shortcut "New message". */
@Implements(LauncherApps::class)
class OneShortcutLauncherApps : ShadowLauncherApps() {
    @Implementation
    override fun getShortcuts(query: LauncherApps.ShortcutQuery, user: UserHandle): List<ShortcutInfo> = listOf(
        ShortcutInfo.Builder(RuntimeEnvironment.getApplication(), "compose")
            .setShortLabel("New message")
            .setIntent(Intent(Intent.ACTION_VIEW))
            .build(),
    )
}

/**
 * The long-press popup on a locked desktop (issue #30, a follow-up). The lock keeps the home screen as it is,
 * not the menu shut: the app's own shortcuts still start and the rows that change nothing on the desktop stay
 * (the caller marks them, [PopupAction.keptWhenLocked]). What would add, move, rename or remove something is
 * not offered: the other rows, the Widgets row and a shortcut's pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en", shadows = [OneShortcutLauncherApps::class])
class AppActionPopupLockedTest {
    @get:Rule val compose = createComposeRule()

    private val app = AppItem("com.example", "Main", Process.myUserHandle(), 0L, "Example")

    /**
     * Review round 12. The menu can be handed its shortcuts ([AppActionPopup]'s `shortcutsOf`), as it can be
     * handed the answer about widgets: Android is then not asked, and nothing of the menu comes back from
     * another thread. A test of the menu's layout failed in a full run because the answer of the real query
     * was written from the thread it was made on. Here Android would answer "New message" to any query.
     */
    @Test fun aMenuHandedItsShortcutsDoesNotAskAndroidForThem() {
        compose.setContent {
            AppActionPopup(
                app = app,
                anchor = IntOffset(200, 40),
                preferAbove = false,
                actions = listOf(PopupAction("App info") {}),
                onDismiss = {},
                shortcutsOf = { emptyList() },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("App info").assertIsDisplayed()
        compose.onNodeWithText("New message").assertDoesNotExist()
    }

    private fun show(locked: Boolean) {
        compose.setContent {
            // The menu asks Android itself here (the shadow answers), on this thread: no wait on another one.
            CompositionLocalProvider(LocalBackgroundDispatcher provides Dispatchers.Unconfined) {
                AppActionPopup(
                    app = app,
                    // Near the window's top: the rows of the unlocked menu need the room under it.
                    anchor = IntOffset(200, 40),
                    preferAbove = false,
                    actions = listOf(
                        PopupAction("App info", keptWhenLocked = true) {},
                        PopupAction("Remove from home") {},
                    ),
                    onDismiss = {},
                    onPinShortcut = {},
                    onWidgets = {},
                    hasWidgets = { true },
                    locked = locked,
                )
            }
        }
        compose.waitForIdle()
    }

    /** The fixture: unlocked, every row this popup can have is there. */
    @Test fun anUnlockedDesktopOffersEveryRow() {
        show(locked = false)

        compose.onNodeWithText("New message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pin to home screen").assertIsDisplayed()
        compose.onNodeWithText("Widgets").assertIsDisplayed()
        compose.onNodeWithText("App info").assertIsDisplayed()
        compose.onNodeWithText("Remove from home").assertIsDisplayed()
    }

    @Test fun aLockedDesktopKeepsTheShortcutsAndTheRowsMarkedForIt() {
        show(locked = true)

        compose.onNodeWithText("New message").assertIsDisplayed()
        compose.onNodeWithText("App info").assertIsDisplayed()
    }

    @Test fun aLockedDesktopOffersNothingThatChangesIt() {
        show(locked = true)

        compose.onNodeWithText("Remove from home").assertDoesNotExist()
        compose.onNodeWithText("Widgets").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pin to home screen").assertDoesNotExist()
    }
}
