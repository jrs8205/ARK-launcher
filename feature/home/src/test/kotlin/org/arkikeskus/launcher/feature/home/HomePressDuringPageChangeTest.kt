package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HOME on home turns the pager to the home page. The turn ran inside an effect keyed on the page
 * count, so a page removed while it was under way (a package removal emptying the last page, or
 * "Remove empty page" right before HOME) cancelled the animation half way and left the pager
 * between two pages until the next touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomePressDuringPageChangeTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val pageCount = mutableStateOf(4)
    private val homeSignals = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val pageRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    @Test fun aPageRemovedDuringTheHomeTurnStillLandsOnTheHomePage() {
        compose.setContent {
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
                homeSignals = homeSignals,
                homePage = 0,
                pageRequests = pageRequests,
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
                onLaunchShortcut = {},
                onRemoveShortcut = {},
                onCreateFolder = { _, _ -> },
                onAddToFolder = { _, _ -> },
                onEmptyAreaMenu = { _, _ -> },
                modifier = Modifier.size(300.dp, 500.dp),
            )
        }
        compose.runOnIdle { pageRequests.tryEmit(2) }
        compose.waitForIdle()
        assertThat(drag.currentPage).isEqualTo(2)

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { homeSignals.tryEmit(true) }
        compose.mainClock.advanceTimeBy(32)
        // The turn has barely begun when the last page (not the one the turn left) goes away.
        compose.runOnIdle { pageCount.value = 3 }
        compose.mainClock.advanceTimeBy(16)
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(0)
    }
}
