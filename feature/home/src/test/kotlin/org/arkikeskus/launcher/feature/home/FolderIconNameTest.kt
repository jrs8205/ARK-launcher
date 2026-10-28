package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #17: the "Show folder names" setting takes the name from under a home folder's tile - only the name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FolderIconNameTest {
    @get:Rule val compose = createComposeRule()

    private val apps = listOf("Mail", "Maps").map { AppItem("com.$it", "Main", Process.myUserHandle(), 0L, it) }

    @Test fun aHiddenNameLeavesTheTileWithoutItsLabel() {
        compose.setContent {
            FolderIcon(name = "Tools", apps = apps, showLabel = true, showName = false, badgeCount = 0, badgeShowCount = false)
        }

        compose.onNodeWithText("Tools").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(1)
    }

    @Test fun theWorkspacePassesTheSettingToItsFolders() {
        val drag = HomeDragController()
        val folder = PlacedFolder(id = 7, name = "Tools", apps = apps, page = 0, cellX = 0, cellY = 0)
        compose.setContent {
            Workspace(
                pageCount = 1,
                columns = 4,
                rows = 6,
                entries = listOf(folder),
                badges = emptyMap(),
                badgeShowCount = false,
                badgeScale = 1f,
                showLabels = true,
                showFolderNames = false,
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
                onMoveToDock = { _, _ -> },
                onRemoveFromHome = {},
                onOpenFolder = {},
                onLaunchShortcut = {},
                onRemoveShortcut = {},
                onCreateFolder = { _, _ -> },
                onAddToFolder = { _, _ -> },
                onEmptyAreaMenu = { _, _ -> },
                modifier = Modifier.size(300.dp, 500.dp),
            )
        }

        compose.onNodeWithText("Tools").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(1)
    }
}
