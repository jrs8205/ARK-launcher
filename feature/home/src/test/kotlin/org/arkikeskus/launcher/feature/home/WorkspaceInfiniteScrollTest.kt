package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.up
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Infinite scrolling end to end on the real workspace (empty pages), observed through the page
 *  the workspace publishes to the drag controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WorkspaceInfiniteScrollTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()

    private fun setWorkspace(
        pageCount: Int,
        homePage: Int,
        infiniteScroll: Boolean,
        leftEdgeAction: Boolean = false,
        entries: List<HomeEntry> = emptyList(),
        widgetViews: Map<Int, AppWidgetHostView> = emptyMap(),
        widgetScrollableById: Map<Int, Boolean> = emptyMap(),
    ) {
        compose.setContent {
            Workspace(
                pageCount = pageCount,
                columns = 4,
                rows = 6,
                entries = entries,
                widgetViews = widgetViews,
                widgetScrollableById = widgetScrollableById,
                badges = emptyMap(),
                badgeShowCount = false,
                badgeScale = 1f,
                showLabels = true,
                showPageIndicator = true,
                locked = false,
                homeSignals = emptyFlow(),
                homePage = homePage,
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
                infiniteScroll = infiniteScroll,
                leftEdgeActionEnabled = leftEdgeAction,
                modifier = Modifier.size(300.dp, 500.dp).testTag("workspace"),
            )
        }
        compose.waitForIdle()
        assertThat(drag.currentPage).isEqualTo(homePage)
    }

    /** An icon from [source] dragged onto the workspace and held at the right edge of the grid — with
     *  [leftSource] false, one that moves without leaving its surface (a reorder inside the folder
     *  card). The writes are pushed out at once: the test clock alone does not deliver writes made
     *  outside a frame. */
    private fun holdDragAtTheRightEdge(token: Any, source: DragSource = DragSource.Dock, leftSource: Boolean = true) {
        compose.runOnUiThread {
            drag.start(AppItem("p", "p.Main", Process.myUserHandle(), 0L, "App"), source, Offset.Zero, token)
            drag.beginMove(leftSource)
            val grid = drag.gridBounds
            drag.update(Offset(grid.right - 2f, grid.center.y))
            Snapshot.sendApplyNotifications()
        }
    }

    private fun swipeOn() = compose.onNodeWithTag("workspace").performTouchInput { swipeLeft() }
    private fun swipeBack() = compose.onNodeWithTag("workspace").performTouchInput { swipeRight() }

    @Test
    fun swipingOnFromTheLastPageCarriesOnToTheFirst() {
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = true)
        swipeOn()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
        compose.onNodeWithText(NO_MORE_PAGES).assertDoesNotExist()
    }

    @Test
    fun swipingBackFromTheFirstPageCarriesOnToTheLast() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = true)
        swipeBack()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(2)
    }

    @Test
    fun theLeftEdgeAppKeepsTheFirstPageFromWrappingBack() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = true, leftEdgeAction = true)
        swipeBack()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
    }

    @Test
    fun withInfiniteScrollOffTheLastPageBouncesBackAndSaysSo() {
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = false)
        swipeOn()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(2)
        compose.onNodeWithText(NO_MORE_PAGES).assertExists()
    }

    @Test
    fun withInfiniteScrollOffAHeldPullPastTheLastPageStaysOnItAndSaysSo() {
        // Like the first page: the stretch and the hint, never the empty drop page.
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = false)
        compose.onNodeWithTag("workspace").performTouchInput {
            down(center)
            repeat(16) { moveBy(Offset(-width * 0.05f, 0f)) }
        }
        compose.mainClock.advanceTimeBy(100)
        assertThat(drag.currentPage).isEqualTo(2)
        compose.onNodeWithText(NO_MORE_PAGES).assertExists()
        compose.onNodeWithTag("workspace").performTouchInput { up() }
    }

    @Test
    fun aDockDragHeldAtTheRightEdgeOfTheLastPageStillOpensTheNewPage() {
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = false)
        holdDragAtTheRightEdge(Any())
        compose.mainClock.advanceTimeBy(1_500) // 550 ms dwell, then the flip
        compose.waitForIdle()
        assertThat(drag.currentPage).isEqualTo(3)
    }

    @Test
    fun aFolderDragPastItsCardFlipsPagesLikeADockDrag() {
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = false)
        holdDragAtTheRightEdge(Any(), source = DragSource.Folder, leftSource = true)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertThat(drag.currentPage).isEqualTo(3)
    }

    @Test
    fun aReorderInsideTheFolderCardNeverFlipsThePage() {
        // The card sits inside the edge-flip zone; a move that has not left it must not page behind it.
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = false)
        holdDragAtTheRightEdge(Any(), source = DragSource.Folder, leftSource = false)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertThat(drag.currentPage).isEqualTo(2)
    }

    @Test
    fun withInfiniteScrollOffSwipingBackOnTheFirstPageSaysSo() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = false)
        swipeBack()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
        compose.onNodeWithText(NO_MORE_PAGES).assertExists()
    }

    @Test
    fun aSinglePageSaysNoMorePagesWhenSwipedBack() {
        setWorkspace(pageCount = 1, homePage = 0, infiniteScroll = true)
        swipeBack()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(NO_MORE_PAGES).assertExists()
    }

    @Test
    fun theLeftEdgeAppGetsNoEndOfPagesHint() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = false, leftEdgeAction = true)
        swipeBack()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(NO_MORE_PAGES).assertDoesNotExist()
    }

    @Test
    fun aSecondFingerPullingBackDuringADockDragGetsNoEndOfPagesHint() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = false)
        // A dock icon held mid-grid by one finger (the pager still scrolls for a dock drag)…
        compose.runOnIdle {
            drag.start(AppItem("p", "p.Main", Process.myUserHandle(), 0L, "App"), DragSource.Dock, Offset.Zero, Any())
            drag.beginMove()
            drag.update(drag.gridBounds.center)
        }
        // …while another finger pulls back on page 0.
        swipeBack()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(NO_MORE_PAGES).assertDoesNotExist()
    }

    /** A dock icon lifted by a still long-press, not moving yet (its menu opens on release). The
     *  pager stays scrollable for a dock lift, so a second finger can still pull it. */
    private fun holdDockIconStill() {
        compose.runOnUiThread {
            drag.start(AppItem("p", "p.Main", Process.myUserHandle(), 0L, "App"), DragSource.Dock, Offset.Zero, Any())
            Snapshot.sendApplyNotifications()
        }
    }

    @Test
    fun aSecondFingerDoesNotWrapBackWhileADockIconIsHeldStill() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = true)
        holdDockIconStill()
        swipeBack()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
    }

    @Test
    fun aSecondFingerDoesNotWrapOnWhileADockIconIsHeldStill() {
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = true)
        holdDockIconStill()
        swipeOn()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(2)
    }

    /** The pager's own node: the one that carries the accessibility page actions. */
    private fun pagerNode() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.PageLeft))

    @Test
    fun theAccessibilityPageBackOnTheFirstPageCarriesOnToTheLast() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = true)
        pagerNode().performSemanticsAction(SemanticsActions.PageLeft)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(2)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // the platform delegate finds its nodes with Region
    fun theAccessibilityScrollBackOnTheFirstPageCarriesOnToTheLast() {
        // The platform action TalkBack and Switch Access send. Compose offers it, and turns it into
        // the page action, only while the scroll position says there is room to go back.
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = true)
        compose.runOnUiThread {
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(AccessibilityManager::class.java)).setEnabled(true)
            composeView().forceAccessibilityForTesting(true)
        }
        val id = pagerNode().fetchSemanticsNode().id
        compose.runOnIdle {
            val provider = composeView().view.accessibilityNodeProvider
            val actions = provider.createAccessibilityNodeInfo(id)!!.actionList.map { it.id }
            assertThat(actions).contains(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            assertThat(provider.performAction(id, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)).isTrue()
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(2)
    }

    @Test
    fun theAccessibilityPageBackFromAMiddlePageGoesBackOnePage() {
        setWorkspace(pageCount = 3, homePage = 1, infiniteScroll = true)
        pagerNode().performSemanticsAction(SemanticsActions.PageLeft)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
    }

    @Test
    fun theLeftEdgeAppKeepsTheAccessibilityPageBackFromWrapping() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = true, leftEdgeAction = true)
        pagerNode().performSemanticsAction(SemanticsActions.PageLeft)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
    }

    @Test
    fun withInfiniteScrollOffTheAccessibilityPageBackStaysOnTheFirstPage() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = false)
        pagerNode().performSemanticsAction(SemanticsActions.PageLeft)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
    }

    /** Runs the pager's accessibility page action [key] and returns whether it was handled. */
    private fun pageAction(key: SemanticsPropertyKey<AccessibilityAction<() -> Boolean>>): Boolean {
        val action = checkNotNull(pagerNode().fetchSemanticsNode().config[key].action)
        return compose.runOnUiThread { action() }
    }

    @Test
    fun theAccessibilityPageOnFromTheLastPageCarriesOnToTheFirst() {
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = true)
        assertThat(pageAction(SemanticsActions.PageRight)).isTrue()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
    }

    @Test
    fun withInfiniteScrollOffTheAccessibilityPageOnStopsAtTheLastPageAndSaysSo() {
        // Like a swipe: never onto the empty drop page.
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = false)
        assertThat(pageAction(SemanticsActions.PageRight)).isFalse()
        compose.mainClock.advanceTimeBy(100)
        assertThat(drag.currentPage).isEqualTo(2)
        compose.onNodeWithText(NO_MORE_PAGES).assertExists()
    }

    @Test
    fun aSinglePageAccessibilityPageOnStaysAndSaysSo() {
        setWorkspace(pageCount = 1, homePage = 0, infiniteScroll = true)
        assertThat(pageAction(SemanticsActions.PageRight)).isFalse()
        compose.mainClock.advanceTimeBy(100)
        assertThat(drag.currentPage).isEqualTo(0)
        compose.onNodeWithText(NO_MORE_PAGES).assertExists()
    }

    @Test
    fun withInfiniteScrollOffTheAccessibilityPageBackOnTheFirstPageSaysSo() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = false)
        assertThat(pageAction(SemanticsActions.PageLeft)).isFalse()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(NO_MORE_PAGES).assertExists()
    }

    @Test
    fun theLeftEdgeAppGetsNoEndOfPagesHintOnTheAccessibilityPageBack() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = false, leftEdgeAction = true)
        assertThat(pageAction(SemanticsActions.PageLeft)).isFalse()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(NO_MORE_PAGES).assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // the platform delegate finds its nodes with Region
    fun withInfiniteScrollOffScrollingOnIsNotOfferedOnTheLastPage() {
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = false)
        compose.runOnUiThread {
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(AccessibilityManager::class.java)).setEnabled(true)
            composeView().forceAccessibilityForTesting(true)
        }
        val id = pagerNode().fetchSemanticsNode().id
        compose.runOnIdle {
            val actions = composeView().view.accessibilityNodeProvider.createAccessibilityNodeInfo(id)!!.actionList.map { it.id }
            assertThat(actions).contains(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            assertThat(actions).doesNotContain(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }

    private fun composeView(): ViewRootForTest {
        fun find(view: View): ViewRootForTest? = when (view) {
            is ViewRootForTest -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return checkNotNull(find((compose as AndroidComposeTestRule<*, *>).activity.window.decorView))
    }

    @Test
    fun wrappingBackSaysNothing() {
        setWorkspace(pageCount = 3, homePage = 0, infiniteScroll = true)
        swipeBack()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(2)
        compose.onNodeWithText(NO_MORE_PAGES).assertDoesNotExist()
    }

    @Test
    fun aDragThatEndsWhileFlippingOntoTheNewPageReturnsToTheLastPage() {
        setWorkspace(pageCount = 3, homePage = 2, infiniteScroll = true)
        val token = Any()
        // A dock→home drag held at the right edge of the last page flips onto the empty new page…
        holdDragAtTheRightEdge(token)
        // …(550 ms dwell, then the flip animates) and ends while that flip is still running.
        compose.mainClock.advanceTimeBy(650)
        compose.runOnUiThread {
            drag.stop(token)
            Snapshot.sendApplyNotifications() // seen at the next frame, mid-flip
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // The new page was the drag's target, not a copy of page 0: it bounces back as before.
        assertThat(drag.currentPage).isEqualTo(2)
    }

    /** Page 0 holding a scrollable app widget (a collection list): the root swipe detector must yield
     *  to it wherever it is on screen — on the real page 0 and on its copy at the tail. */
    private lateinit var hostView: AppWidgetHostView

    private fun setWorkspaceWithScrollableWidgetOnPageZero(homePage: Int = 2) {
        hostView = AppWidgetHostView(RuntimeEnvironment.getApplication())
        setWorkspace(
            pageCount = 3,
            homePage = homePage,
            infiniteScroll = true,
            entries = listOf(PlacedWidget(WIDGET_ROW, WIDGET_ID, ComponentName("p", "p.Widget"), page = 0, cellX = 0, cellY = 0, spanX = 4, spanY = 3)),
            widgetViews = mapOf(WIDGET_ID to hostView),
            widgetScrollableById = mapOf(WIDGET_ID to true),
        )
    }

    @Test
    fun theCopyOfPageZeroOnScreenHostsItsWidget() {
        setWorkspaceWithScrollableWidgetOnPageZero()
        // The workspace opened on the last page, so page 0 has never been composed: the copy sliding
        // in is the only place the widget's view can be.
        compose.onNodeWithTag("workspace").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width * 0.05f, 0f)) }
        }
        compose.mainClock.advanceTimeBy(100)
        assertThat(hostView.parent).isNotNull()
        assertThat(hostView.isAttachedToWindow).isTrue()
        compose.onNodeWithTag("workspace").performTouchInput { up() }
    }

    @Test
    fun theCopyOfPageZeroHostsItsWidgetWhileTheAccessibilityPageBackWraps() {
        setWorkspaceWithScrollableWidgetOnPageZero(homePage = 0)
        pagerNode().performSemanticsAction(SemanticsActions.PageLeft)
        compose.mainClock.advanceTimeBy(100) // onto the copy, and sliding from it back onto the last page
        assertThat(hostView.isAttachedToWindow).isTrue()
        val rects = registeredRects()
        assertThat(rects).hasSize(1)
        assertThat(rects.single().left).isGreaterThan(0f) // the copy's widget, sliding out to the right
    }

    @Test
    fun theCopyOfPageZeroOnScreenKeepsItsScrollableWidgetFromTheRootSwipe() {
        setWorkspaceWithScrollableWidgetOnPageZero()
        // Hold the slide half-way from the last page onto the copy of page 0 (the real page 0 is not
        // composed): a new vertical swipe could start on the copy's widget now.
        compose.onNodeWithTag("workspace").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width * 0.05f, 0f)) }
        }
        compose.mainClock.advanceTimeBy(100)
        val rects = registeredRects()
        assertThat(rects).hasSize(1)
        assertThat(rects.single().left).isGreaterThan(0f) // the copy's widget, sliding in from the right
        compose.onNodeWithTag("workspace").performTouchInput { up() }
    }

    @Test
    fun slidingBackOffTheCopyLeavesNoRectBehindOnTheLastPage() {
        setWorkspaceWithScrollableWidgetOnPageZero()
        compose.onNodeWithTag("workspace").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-width * 0.05f, 0f)) } // the copy half on screen
            repeat(10) { moveBy(Offset(width * 0.05f, 0f)) } // and back off it
            advanceEventTime(500) // then rest, so the release carries no fling
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(2)
        // The last page has no scrollable widget: a stale rect would swallow its swipe-up.
        assertThat(registeredRects()).isEmpty()
    }

    @Test
    fun afterTheWrapOnlyTheRealPageZeroWidgetIsRegistered() {
        setWorkspaceWithScrollableWidgetOnPageZero()
        swipeOn()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
        // The copy's disposal must not take the real page 0's registration with it, nor leave its own.
        assertThat(drag.scrollableWidgetRects.keys).containsExactly(WIDGET_ROW)
        assertThat(registeredRects().single().left).isEqualTo(0f)
    }

    private fun registeredRects() = drag.scrollableWidgetRects.values.filterNot { it.isEmpty }

    @Test
    fun aSinglePageBouncesBackEvenWithInfiniteScroll() {
        setWorkspace(pageCount = 1, homePage = 0, infiniteScroll = true)
        swipeOn()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(drag.currentPage).isEqualTo(0)
        compose.onNodeWithText(NO_MORE_PAGES).assertExists()
    }

    private companion object {
        const val WIDGET_ROW = 7L
        const val WIDGET_ID = 42
        const val NO_MORE_PAGES = "No more pages"
    }
}
