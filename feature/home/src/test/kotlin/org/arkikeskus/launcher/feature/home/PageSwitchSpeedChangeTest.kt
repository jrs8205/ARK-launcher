package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #18: the page-switch speed is read by long-lived effects (the page-request collector, the
 * HOME collector, the drag edge flips) that outlive the composition which started them. A speed
 * changed in Settings must reach the next page change they make, not only the next swipe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PageSwitchSpeedChangeTest {
    @get:Rule val compose = createComposeRule()

    private val speed = mutableStateOf(LauncherSettings.SPEED_NORMAL)
    private val homeSignals = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val pageRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    @Test fun aSpeedChangeReachesARequestedPageChange() {
        composeWorkspace()
        compose.runOnIdle { speed.value = LauncherSettings.SPEED_FASTEST }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { pageRequests.tryEmit(2) }
        compose.mainClock.advanceTimeBy(250)

        assertThat(pagerPosition()).isEqualTo(2f)
    }

    @Test fun aSpeedChangeReachesTheHomePressTurn() {
        composeWorkspace()
        compose.runOnIdle { pageRequests.tryEmit(2) }
        compose.waitForIdle()
        assertThat(pagerPosition()).isEqualTo(2f)
        compose.runOnIdle { speed.value = LauncherSettings.SPEED_FASTEST }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { homeSignals.tryEmit(true) }
        compose.mainClock.advanceTimeBy(250)

        assertThat(pagerPosition()).isEqualTo(0f)
    }

    private fun composeWorkspace() {
        val drag = HomeDragController()
        compose.setContent {
            CompositionLocalProvider(LocalPageSwitchSpeed provides speed.value) {
                Workspace(
                    pageCount = 4,
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
        }
        compose.waitForIdle()
    }

    /** The pager's position in pages, from its accessibility scroll range (3 = the last page). */
    private fun pagerPosition(): Float {
        val range = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        return range.value() / (range.maxValue() / 3f)
    }
}
