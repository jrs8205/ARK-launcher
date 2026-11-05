package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #23: a still long-press on a pinned shortcut asks to edit it (its own icon), the way a still
 * press on a folder opens it; a tap still launches the shortcut, and a hold that moves lifts it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WorkspaceShortcutEditTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val launched = mutableListOf<PlacedShortcut>()
    private val edited = mutableListOf<PlacedShortcut>()
    private var longPress = 0L

    private val shortcut = PlacedShortcut(
        rowId = 1, packageName = "org.mozilla.firefox", shortcutId = "https://github.com/", userSerial = 0L, label = "GitHub",
        icon = null, enabled = true, disabledMessage = null, page = 0, cellX = 0, cellY = 0,
    )

    private fun show() {
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            Workspace(
                pageCount = 1, columns = 4, rows = 6, entries = listOf(shortcut),
                badges = emptyMap(), badgeShowCount = false, badgeScale = 1f, showLabels = true, showPageIndicator = true,
                locked = false, homeSignals = emptyFlow(), homePage = 0, dragController = drag,
                widgetDragController = remember { WidgetDragController(drag) },
                onAddWidget = { _, _ -> }, onAppClick = {}, onAppMenu = { _, _, _ -> },
                onMove = { _, _, _, _ -> true }, onMoveFolder = { _, _, _, _ -> true }, onDropOnDock = { _, _ -> },
                onRemoveFromHome = {}, onOpenFolder = {}, onLaunchShortcut = { launched += it }, onRemoveShortcut = {},
                onCreateFolder = { _, _ -> }, onAddToFolder = { _, _ -> }, onEmptyAreaMenu = { _, _ -> },
                onEditShortcut = { edited += it },
                modifier = Modifier.size(300.dp, 500.dp),
            )
        }
        compose.waitForIdle()
    }

    @Test fun aStillLongPressAsksToEditTheShortcut() {
        show()

        compose.onNodeWithText("GitHub").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 100)
        compose.waitForIdle()
        compose.onNodeWithText("GitHub").performTouchInput { up() }
        compose.waitForIdle()

        assertThat(edited).containsExactly(shortcut)
        assertThat(launched).isEmpty()
    }

    @Test fun aTapStillLaunchesIt() {
        show()

        compose.onNodeWithText("GitHub").performTouchInput { click() }
        compose.waitForIdle()

        assertThat(launched).containsExactly(shortcut)
        assertThat(edited).isEmpty()
    }
}
