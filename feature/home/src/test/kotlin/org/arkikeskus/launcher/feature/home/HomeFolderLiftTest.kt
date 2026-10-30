package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A home folder held and pulled lifts in the shared controller as a folder from home (so the dock can
 * take it), and its floating copy is the host's to draw above every surface: the grid draws none of
 * its own, which the dock, composed after the grid, used to cover right where the folder would drop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeFolderLiftTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val chat = AppItem("com.chat", "chat.Main", Process.myUserHandle(), 0L, "Chat")
    private val tools = PlacedFolder(id = 7L, name = "Tools", apps = listOf(mail, chat), page = 0, cellX = 0, cellY = 0)
    private var longPress = 0L

    @Test fun aLiftedHomeFolderIsAFolderDragFromHomeWithoutAGridCopy() {
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            Workspace(
                pageCount = 1, columns = 4, rows = 6, entries = listOf(tools),
                badges = emptyMap(), badgeShowCount = false, badgeScale = 1f, showLabels = true, showPageIndicator = true,
                locked = false, homeSignals = emptyFlow(), homePage = 0, dragController = drag,
                widgetDragController = remember { WidgetDragController(drag) },
                onAddWidget = { _, _ -> }, onAppClick = {}, onAppMenu = { _, _, _ -> },
                onMove = { _, _, _, _ -> true }, onMoveFolder = { _, _, _, _ -> true }, onDropOnDock = { _, _ -> },
                onRemoveFromHome = {}, onOpenFolder = {}, onLaunchShortcut = {}, onRemoveShortcut = {},
                onCreateFolder = { _, _ -> }, onAddToFolder = { _, _ -> }, onEmptyAreaMenu = { _, _ -> },
                modifier = Modifier.size(300.dp, 320.dp),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Tools").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(drag.draggedFolderId).isEqualTo(7L)
        assertThat(drag.source).isEqualTo(DragSource.Home)

        compose.onNodeWithText("Tools").performTouchInput { repeat(8) { moveBy(Offset(30f, 30f)) } }
        compose.waitForIdle()

        assertThat(drag.moving).isTrue()
        assertThat(drag.isIncomingFrom(DragSource.Home)).isFalse() // a local move, never "incoming" to the grid
        // The tile (hidden while moving) is the only node named Tools: no floating copy of the grid's own.
        compose.onAllNodesWithText("Tools").assertCountEquals(1)

        compose.onNodeWithText("Tools").performTouchInput { up() }
        compose.waitForIdle()
        assertThat(drag.isDragging).isFalse()
    }
}
