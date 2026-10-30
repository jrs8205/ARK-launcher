package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Show the new item": a row another app's pin request added is revealed the next time the home
 * screen comes to the foreground at rest — the workspace turns to its page and the hand-over is
 * consumed — and waits for a later time when it isn't.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WorkspaceRevealTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val newItems = NewHomeItems()

    /** Read by the workspace on every composition, so a test can open the drawer mid-way. */
    private val drawerOpenState = mutableStateOf(false)

    private fun shortcut(rowId: Long, page: Int, label: String = "Mia") = PlacedShortcut(
        rowId = rowId, packageName = "com.chat", shortcutId = "s$rowId", userSerial = 0L, label = label,
        icon = null, enabled = true, disabledMessage = null, page = page, cellX = 0, cellY = 0,
    )

    /** A widget row whose provider Robolectric knows nothing about: it renders as "Widget unavailable". */
    private fun widget(rowId: Long, page: Int) = PlacedWidget(
        rowId = rowId, appWidgetId = rowId.toInt(), provider = ComponentName("com.clock", "Analog"),
        page = page, cellX = 0, cellY = 2, spanX = 2, spanY = 2,
    )

    private fun setWorkspace(
        entries: List<HomeEntry>,
        pageCount: Int = 3,
        drawerOpen: Boolean = false,
        homeSignals: Flow<Boolean> = emptyFlow(),
    ) {
        drawerOpenState.value = drawerOpen
        compose.setContent {
            Workspace(
                pageCount = pageCount,
                columns = 4,
                rows = 6,
                entries = entries,
                badges = emptyMap(),
                badgeShowCount = false,
                badgeScale = 1f,
                showLabels = true,
                showPageIndicator = true,
                locked = false,
                homeSignals = homeSignals,
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
                onLaunchShortcut = {},
                onRemoveShortcut = {},
                onCreateFolder = { _, _ -> },
                onAddToFolder = { _, _ -> },
                onEmptyAreaMenu = { _, _ -> },
                drawerOpen = drawerOpenState.value,
                newItems = newItems,
                modifier = Modifier.size(300.dp, 500.dp).testTag("workspace"),
            )
        }
        compose.waitForIdle()
    }

    /**
     * The reveal with the clock held: the page turn is under way (not finished) when this returns.
     * Two new rows, the last on page 1, so the turn goes 0 → 1 and the first row stays on page 0,
     * where every interruption below leaves the pager.
     */
    private fun startRevealTowardsPageOne(homeSignals: Flow<Boolean> = emptyFlow()) {
        compose.mainClock.autoAdvance = false
        newItems.add(5L)
        newItems.add(6L)
        setWorkspace(listOf(shortcut(5L, page = 0), shortcut(6L, page = 1, label = "Tom")), pageCount = 2, homeSignals = homeSignals)
        compose.mainClock.advanceTimeBy(120)
        // Mid-turn: the row is still hidden (its node has no size yet).
        compose.onNodeWithText("Mia").assertIsNotDisplayed()
    }

    @Test fun aSwipeDuringThePageTurnStillLeavesTheNewItemFullyVisible() {
        startRevealTowardsPageOne()

        compose.onNodeWithTag("workspace").performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(0)
        compose.onNodeWithText("Mia").assertIsDisplayed()
        assertThat(newItems.take()).isEmpty()
    }

    /**
     * A reveal that has to wait for a row (one of the new rows was removed meanwhile, so the wait
     * runs its full length): the first new row is on page 2, and the clock is held at the start of
     * the wait when this returns.
     */
    private fun startRevealThatWaits(homeSignals: Flow<Boolean> = emptyFlow()) {
        compose.mainClock.autoAdvance = false
        newItems.add(5L)
        newItems.add(99L) // removed before the home screen came back: never reaches the grid
        setWorkspace(listOf(shortcut(5L, page = 2)), homeSignals = homeSignals)
        compose.mainClock.advanceTimeBy(500)
        assertThat(drag.currentPage).isEqualTo(0)
    }

    @Test fun aHomePressWhileTheRevealWaitsKeepsThePageWhereHomeLeftIt() {
        val homeSignals = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        startRevealThatWaits(homeSignals)

        homeSignals.tryEmit(true)
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(0)
        assertThat(newItems.take()).isEmpty()
    }

    @Test fun openingTheDrawerWhileTheRevealWaitsKeepsThePage() {
        startRevealThatWaits()

        drawerOpenState.value = true
        Snapshot.sendApplyNotifications() // a write from the test thread reaches the composition only then
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(0)
    }

    @Test fun aDrawerOpenedAndClosedAgainWhileTheRevealWaitsStillKeepsThePage() {
        startRevealThatWaits()

        drawerOpenState.value = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(500)
        drawerOpenState.value = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(0)
    }

    @Test fun aPageTheUserTurnedToWhileTheRevealWaitsIsKept() {
        startRevealThatWaits()

        compose.onNodeWithTag("workspace").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(1)
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(1)
    }

    @Test fun aHomePressDuringThePageTurnStillLeavesTheNewItemFullyVisible() {
        val homeSignals = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        startRevealTowardsPageOne(homeSignals)

        // HOME while already on home: the workspace animates back to the home page, which cancels
        // the reveal's own page turn in the pager.
        homeSignals.tryEmit(true)
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(0)
        compose.onNodeWithText("Mia").assertIsDisplayed()
    }

    @Test fun aWidgetRowPopsInLikeAShortcut() {
        compose.mainClock.autoAdvance = false
        newItems.add(7L)
        setWorkspace(listOf(widget(7L, page = 0)), pageCount = 1)
        compose.mainClock.advanceTimeBy(50)

        // Hidden until its pop, like a shortcut row...
        compose.onNodeWithText("Widget unavailable").assertIsNotDisplayed()

        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()

        // ...and fully visible once the reveal is over.
        compose.onNodeWithText("Widget unavailable").assertIsDisplayed()
        assertThat(newItems.take()).isEmpty()
    }

    @Test fun theWorkspaceTurnsToTheNewItemsPageAndTheHandOverIsConsumed() {
        newItems.add(5L)
        setWorkspace(listOf(shortcut(5L, page = 2)))

        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(2)
        assertThat(newItems.take()).isEmpty()
        compose.onNodeWithText("Mia").assertExists()
    }

    @Test fun severalNewItemsTurnToTheLastOnesPage() {
        newItems.add(5L)
        newItems.add(6L)
        setWorkspace(listOf(shortcut(5L, page = 2), shortcut(6L, page = 1, label = "Tom")))

        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(1)
        assertThat(newItems.take()).isEmpty()
    }

    @Test fun aRowRemovedMeanwhileIsSkipped() {
        newItems.add(5L)
        newItems.add(99L) // deleted before the home screen came back
        setWorkspace(listOf(shortcut(5L, page = 2)))

        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(2)
        assertThat(newItems.take()).isEmpty()
    }

    @Test fun withTheDrawerOpenTheHandOverWaitsForTheNextTime() {
        newItems.add(5L)
        setWorkspace(listOf(shortcut(5L, page = 2)), drawerOpen = true)

        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(0)
        assertThat(newItems.take()).containsExactly(5L)
    }

    @Test fun nothingNewLeavesThePageAlone() {
        setWorkspace(listOf(shortcut(5L, page = 2)))

        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(0)
    }
}
