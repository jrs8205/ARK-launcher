package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.os.Process
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

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

    /** Issue #18 round 3: the accessibility page actions the wrap-around semantics left to the pager
     *  (paging on with infinite scroll; both directions when the left-edge app owns page 0's back)
     *  ran at the pager's own default (a medium spring, about 200 ms over a page), whatever the
     *  setting. With the fastest setting a page turn is at rest well inside 120 ms; the pager's
     *  default is still some 16 px short then. */
    @Test fun theAccessibilityPageOnWithInfiniteScrollUsesTheChosenSpeed() {
        speed.value = LauncherSettings.SPEED_FASTEST
        composeWorkspace(infiniteScroll = true)
        assertThat(settledWithin(120, "Maps") { pagerNode().performSemanticsAction(SemanticsActions.PageRight) }).isTrue()
    }

    @Test fun theAccessibilityPagingBesideTheLeftEdgeAppUsesTheChosenSpeed() {
        speed.value = LauncherSettings.SPEED_FASTEST
        composeWorkspace(infiniteScroll = true, leftEdgeActionEnabled = true)
        assertThat(settledWithin(120, "Maps") { pagerNode().performSemanticsAction(SemanticsActions.PageRight) }).isTrue()
        assertThat(settledWithin(120, "Mail") { pagerNode().performSemanticsAction(SemanticsActions.PageLeft) }).isTrue()
    }

    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")

    private fun pagerNode() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.PageLeft))

    /** Where the label [text] sits (its left edge in the root), or NaN while it is off screen. */
    private fun labelLeft(text: String): Float =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left ?: Float.NaN

    /** Whether the page change [action] starts has come to rest [millis] in: the label [text] on the
     *  page it turns to is already where it ends up once the pager has settled for good. */
    private fun settledWithin(millis: Long, text: String, action: () -> Unit): Boolean {
        compose.mainClock.autoAdvance = false
        action()
        compose.mainClock.advanceTimeBy(millis)
        val early = labelLeft(text)
        compose.mainClock.advanceTimeBy(3_000)
        val rested = labelLeft(text)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        return !early.isNaN() && abs(early - rested) < 0.5f
    }

    private fun composeWorkspace(infiniteScroll: Boolean = false, leftEdgeActionEnabled: Boolean = false) {
        val drag = HomeDragController()
        compose.setContent {
            CompositionLocalProvider(LocalPageSwitchSpeed provides speed.value) {
                Workspace(
                    pageCount = 4,
                    columns = 4,
                    rows = 6,
                    entries = listOf(PlacedApp(mail, page = 0, cellX = 0, cellY = 0), PlacedApp(maps, page = 1, cellX = 0, cellY = 0)),
                    badges = emptyMap(),
                    badgeShowCount = false,
                    badgeScale = 1f,
                    showLabels = true,
                    showPageIndicator = true,
                    infiniteScroll = infiniteScroll,
                    leftEdgeActionEnabled = leftEdgeActionEnabled,
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
