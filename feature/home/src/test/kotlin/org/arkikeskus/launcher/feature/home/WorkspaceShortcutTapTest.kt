package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
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
 * A tap on a pinned shortcut launches it as it is now. The shortcut's gesture detector outlives the
 * composition that made it (its keys leave the entry out on purpose, so a change can't cut a hold
 * short), so it must not hand on the entry it was created with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WorkspaceShortcutTapTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val launched = mutableListOf<PlacedShortcut>()
    private var entries by mutableStateOf<List<HomeEntry>>(emptyList())

    /** The same home row, as the app has it enabled or disabled (no icon: the label is the tap target). */
    private fun shortcut(enabled: Boolean) = PlacedShortcut(
        rowId = 1, packageName = "com.chat", shortcutId = "mia", userSerial = 0L, label = "Mia",
        icon = null, enabled = enabled, disabledMessage = if (enabled) null else "Mia is away",
        page = 0, cellX = 0, cellY = 0,
    )

    private fun setWorkspace(shortcut: PlacedShortcut) {
        entries = listOf(shortcut)
        compose.setContent {
            Workspace(
                pageCount = 1,
                columns = 4,
                rows = 6,
                entries = entries,
                badges = emptyMap(),
                badgeShowCount = false,
                badgeScale = 1f,
                showLabels = true,
                showPageIndicator = true,
                locked = false,
                homeSignals = emptyFlow(),
                homePage = 0,
                dragController = drag,
                widgetDragController = remember { WidgetDragController(drag) },
                onAddWidget = { _, _ -> },
                onAppClick = {},
                onAppMenu = { _, _, _ -> },
                onMove = { _, _, _, _ -> true },
                onMoveFolder = { _, _, _, _ -> true },
                onDropOnDock = { _, _ -> },
                onRemoveFromHome = {},
                onOpenFolder = {},
                onLaunchShortcut = { launched += it },
                onRemoveShortcut = {},
                onCreateFolder = { _, _ -> },
                onAddToFolder = { _, _ -> },
                onEmptyAreaMenu = { _, _ -> },
                modifier = Modifier.size(300.dp, 500.dp),
            )
        }
        compose.waitForIdle()
    }

    private fun tapShortcut() {
        compose.onNodeWithText("Mia").performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun show(shortcut: PlacedShortcut) {
        compose.runOnIdle { entries = listOf(shortcut) }
        compose.waitForIdle()
    }

    @Test
    fun aShortcutTheAppEnabledAgainIsLaunchedAsEnabled() {
        setWorkspace(shortcut(enabled = false))
        tapShortcut() // refused with the app's reason, as it should be
        show(shortcut(enabled = true))

        tapShortcut()

        assertThat(launched.map { it.enabled }).containsExactly(false, true).inOrder()
    }

    @Test
    fun aShortcutTheAppDisabledIsLaunchedAsDisabled() {
        setWorkspace(shortcut(enabled = true))
        tapShortcut()
        show(shortcut(enabled = false))

        tapShortcut()

        assertThat(launched.map { it.enabled }).containsExactly(true, false).inOrder()
        assertThat(launched.last().disabledMessage).isEqualTo("Mia is away")
    }
}
