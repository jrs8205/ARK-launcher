package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After the launcher process died behind another app, coming home recreates the workspace with its
 * saved state before the layout is known: the first frame measures the pager with the placeholder
 * page count, which clamps the restored page to index 1. When the real count arrives the pager has
 * to go back to the page the user left — neither page 2 nor the home page is where they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WorkspaceRestoredPageTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val pageCount = mutableStateOf(4)
    private val homePage = mutableStateOf<Int?>(0)
    private val pageRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    @Test fun theWorkspaceComesBackOnThePageTheUserLeft() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            Workspace(
                pageCount = pageCount.value,
                columns = 4,
                rows = 6,
                entries = emptyList(),
                badges = emptyMap(),
                badgeShowCount = false,
                badgeScale = 1f,
                showLabels = true,
                showPageIndicator = true,
                locked = false,
                homeSignals = emptyFlow(),
                homePage = homePage.value,
                pageRequests = pageRequests,
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
        compose.runOnIdle { pageRequests.tryEmit(3) }
        compose.waitForIdle()
        assertThat(drag.currentPage).isEqualTo(3)

        // The process died: the new one's first frame knows no layout yet, then the rows arrive.
        pageCount.value = 1
        homePage.value = null
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        pageCount.value = 4
        homePage.value = 0
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(3)
    }
}
