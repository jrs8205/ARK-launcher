package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Process
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.component.LocalProfileMarks
import org.arkikeskus.launcher.ui.component.profileMarks
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.ui.expressive.ExpressiveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Work tab's own pieces: the Personal / Work tab row, the floating pause button, the paused card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text layout: the wrapping tests measure it
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WorkProfileUiTest {
    @get:Rule val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private val personalAndWork = listOf(DrawerTab.Personal, DrawerTab.Work)
    private val allThree = listOf(DrawerTab.Personal, DrawerTab.Work, DrawerTab.Private)
    private val personalAndPrivate = listOf(DrawerTab.Personal, DrawerTab.Private)

    @Test fun tabRowShowsPersonalAndWorkWithTabSemantics() {
        compose.setContent { ExpressiveTheme { DrawerTabRow(personalAndWork, selected = DrawerTab.Personal, onSelect = {}, privateLocked = false) } }

        compose.onNodeWithText("Personal").assert(hasRole(Role.Tab)).assertIsSelected()
        compose.onNodeWithText("Work").assert(hasRole(Role.Tab))
    }

    @Test fun aWorkTwinAmongTheSearchResultsSaysItsProfileToAScreenReader() {
        // A search lists both profiles' apps under one heading; on screen only the work badge tells the twins apart.
        val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0, "Mail")
        val work = mail.copy(userSerial = 12)
        compose.setContent {
            ExpressiveTheme {
                CompositionLocalProvider(LocalProfileMarks provides profileMarks(12, stringResource(R.string.drawer_tab_work))) {
                    val haptics = LocalHapticFeedback.current
                    val drag = remember { HomeDragController() }
                    LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.size(320.dp, 300.dp)) {
                        appCells(listOf(mail, work), emptyMap(), true, 1f, true, 56.dp, {}, { _, _ -> }, drag, {},
                            { _, _, _, _ -> }, { _, _ -> }, haptics, false)
                    }
                }
            }
        }
        compose.waitForIdle()

        // The picture and the label of each say the name: the work one's with its profile, the other's bare.
        compose.onAllNodesWithContentDescription("Mail, Work").assertCountEquals(1)
        compose.onAllNodesWithText("Mail, Work").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(1)
        compose.onAllNodesWithText("Mail").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h640dp")
    fun tabRowGrowsWhenALabelWrapsAtALargeFontSize() {
        // "Professionnel" at 200 % on a 320 dp screen takes two lines: a fixed 40 dp row cut the
        // second one off. The same label on its own at the segment's width is the floor.
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                ExpressiveTheme {
                    Column(Modifier.width(320.dp)) {
                        DrawerTabRow(personalAndWork, selected = DrawerTab.Personal, onSelect = {}, privateLocked = false)
                        Box(Modifier.width(148.dp).testTag("probe")) {
                            Text("Professionnel", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val labelHeight = compose.onNodeWithTag("probe").getBoundsInRoot().height.value
        val rowHeight = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
            .getBoundsInRoot().height.value
        assertThat(labelHeight).isGreaterThan(40f) // the premise: the label really wraps here
        assertThat(rowHeight).isAtLeast(labelHeight)
    }

    @Test fun tappingWorkSelectsIt() {
        var selected: DrawerTab? = null
        compose.setContent { ExpressiveTheme { DrawerTabRow(personalAndWork, selected = DrawerTab.Personal, onSelect = { selected = it }, privateLocked = false) } }

        compose.onNodeWithText("Work").performClick()

        assertThat(selected).isEqualTo(DrawerTab.Work)
    }

    private var selected = DrawerTab.Personal

    /** The tab row over a pager of [tabs], on a paused clock; the stored tab lands in [selected]. */
    private fun pausedTabPager(tabs: List<DrawerTab> = personalAndWork): PagerState {
        compose.mainClock.autoAdvance = false
        lateinit var pagerState: PagerState
        compose.setContent {
            ExpressiveTheme {
                Column {
                    pagerState = rememberPagerState { tabs.size }
                    PagerTabRow(pagerState, tabs, onSelectTab = { selected = it }, privateLocked = false)
                    HorizontalPager(pagerState, Modifier.size(320.dp, 400.dp).testTag("pager")) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        return pagerState
    }

    private fun label(tab: DrawerTab) = when (tab) {
        DrawerTab.Personal -> "Personal"
        DrawerTab.Work -> "Work"
        DrawerTab.Private -> "Private"
    }

    private fun tap(tab: DrawerTab) = compose.onNodeWithText(label(tab)).performClick()

    // The tabs are the ones that exist right now, and a page's number is its place among them.

    @Test fun threeTabsArePagedInTheirOrder() {
        val pagerState = pausedTabPager(allThree)

        tap(DrawerTab.Private)
        compose.mainClock.advanceTimeBy(5_000)
        assertThat(pagerState.currentPage).isEqualTo(2)
        assertThat(selected).isEqualTo(DrawerTab.Private)
        compose.onNodeWithText("Private").assert(hasRole(Role.Tab)).assertIsSelected()

        tap(DrawerTab.Work)
        compose.mainClock.advanceTimeBy(5_000)
        assertThat(pagerState.currentPage).isEqualTo(1)
        assertThat(selected).isEqualTo(DrawerTab.Work)
    }

    @Test fun withoutAWorkProfileThePrivateTabIsTheSecondPage() {
        val pagerState = pausedTabPager(personalAndPrivate)
        compose.onNodeWithText("Work").assertDoesNotExist()

        tap(DrawerTab.Private)
        compose.mainClock.advanceTimeBy(5_000)

        assertThat(pagerState.currentPage).isEqualTo(1)
        assertThat(selected).isEqualTo(DrawerTab.Private)
    }

    @Test fun thePrivateTabCarriesALockMarkOnlyWhileLocked() {
        var locked by mutableStateOf(true)
        compose.setContent {
            ExpressiveTheme { DrawerTabRow(allThree, selected = DrawerTab.Personal, onSelect = {}, privateLocked = locked) }
        }
        compose.onNodeWithTag(PRIVATE_TAB_LOCK_TAG, useUnmergedTree = true).assertExists()

        locked = false
        compose.onNodeWithTag(PRIVATE_TAB_LOCK_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /** The drawer's pages for a list of tabs that changes; each page says which tab it is. */
    private fun pages(tabs: () -> List<DrawerTab>) {
        compose.setContent {
            ExpressiveTheme {
                Column(Modifier.size(320.dp, 500.dp)) {
                    DrawerTabPages(
                        tabs = tabs(),
                        storedTab = selected,
                        onSelectTab = { selected = it },
                        privateLocked = false,
                        userScrollEnabled = true,
                    ) { tab -> Box(Modifier.fillMaxSize()) { Text("page of ${label(tab)}") } }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun withOnlyThePersonalTabThereIsNoTabRowAndNoPager() {
        pages { listOf(DrawerTab.Personal) }

        compose.onNodeWithText("page of Personal").assertIsDisplayed()
        compose.onNode(hasRole(Role.Tab)).assertDoesNotExist()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertDoesNotExist()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertDoesNotExist()
    }

    @Test fun aTabThatGoesWhileItIsOnScreenLeavesThePersonalPage() {
        var tabs by mutableStateOf(allThree)
        pages { tabs }
        tap(DrawerTab.Private)
        compose.waitForIdle()
        compose.onNodeWithText("page of Private").assertIsDisplayed()

        // The private space locks with hiding on: its tab is gone.
        tabs = personalAndWork
        compose.waitForIdle()

        compose.onNodeWithText("page of Personal").assertIsDisplayed()
        compose.onNodeWithText("Personal").assertIsSelected()
        compose.onNodeWithText("Private").assertDoesNotExist()
        assertThat(selected).isEqualTo(DrawerTab.Personal)
    }

    @Test fun aTabThatAppearsToTheLeftLeavesThePageOnScreenAlone() {
        var tabs by mutableStateOf(personalAndPrivate)
        pages { tabs }
        tap(DrawerTab.Private)
        compose.waitForIdle()
        compose.onNodeWithText("page of Private").assertIsDisplayed()

        // A work profile appears: Private moves from the second page to the third.
        tabs = allThree
        compose.waitForIdle()

        compose.onNodeWithText("page of Private").assertIsDisplayed()
        compose.onNodeWithText("Private").assertIsSelected()
        assertThat(selected).isEqualTo(DrawerTab.Private)
    }

    @Test fun theLastOtherTabGoingLeavesThePlainPersonalPage() {
        var tabs by mutableStateOf(personalAndWork)
        pages { tabs }
        tap(DrawerTab.Work)
        compose.waitForIdle()

        tabs = listOf(DrawerTab.Personal)
        compose.waitForIdle()

        compose.onNodeWithText("page of Personal").assertIsDisplayed()
        compose.onNode(hasRole(Role.Tab)).assertDoesNotExist()
        assertThat(selected).isEqualTo(DrawerTab.Personal)
    }

    @Test fun aTappedTabIsHighlightedAtOnceAndStoredOnceThePagerArrives() {
        val pagerState = pausedTabPager()

        tap(DrawerTab.Work)
        compose.mainClock.advanceTimeUntil { pagerState.currentPageOffsetFraction > 0f }
        compose.onNodeWithText("Work").assertIsSelected() // highlighted while still travelling
        compose.mainClock.advanceTimeBy(5_000)

        assertThat(pagerState.currentPage).isEqualTo(1)
        assertThat(selected).isEqualTo(DrawerTab.Work)
    }

    @Test fun aTabTappedBackBeforeTheAnimationMidpointStillSettlesOnThatTab() {
        // Personal -> Work starts the pager animation; Personal tapped before the midpoint cancels
        // it with currentPage still Personal. The pager must travel back, not rest mid-way.
        val pagerState = pausedTabPager()

        tap(DrawerTab.Work)
        compose.mainClock.advanceTimeUntil { pagerState.currentPageOffsetFraction > 0f }
        assertThat(pagerState.currentPage).isEqualTo(0) // the premise: cancelled before the midpoint
        tap(DrawerTab.Personal)
        compose.mainClock.advanceTimeBy(5_000)

        assertThat(pagerState.currentPage).isEqualTo(0)
        assertThat(pagerState.currentPageOffsetFraction).isEqualTo(0f)
        assertThat(selected).isEqualTo(DrawerTab.Personal)
        compose.onNodeWithText("Personal").assertIsSelected()
    }

    @Test fun aTabTappedBackAfterTheAnimationMidpointStillSettlesOnThatTab() {
        // The cancelled animation rests for a moment on the page it had reached (Work). With the
        // stored tab driving the pager, that rest reported Work and the pager went back over the tap.
        val pagerState = pausedTabPager()

        tap(DrawerTab.Work)
        compose.mainClock.advanceTimeUntil { pagerState.currentPage == 1 }
        assertThat(pagerState.isScrollInProgress).isTrue() // the premise: past the midpoint, still moving
        tap(DrawerTab.Personal)
        compose.mainClock.advanceTimeBy(5_000)

        assertThat(pagerState.currentPage).isEqualTo(0)
        assertThat(selected).isEqualTo(DrawerTab.Personal)
        compose.onNodeWithText("Personal").assertIsSelected()
    }

    @Test fun aDragThatInterruptsTheAnimationAndSettlesBackMakesThatTheTabAgain() {
        // A drag back to Personal before the midpoint ends with the settled page unchanged (0): a sync
        // that reported only a CHANGED settled page never fired, and the row showed Work over the
        // Personal grid, with a Work tap doing nothing.
        val pagerState = pausedTabPager()

        tap(DrawerTab.Work)
        compose.mainClock.advanceTimeUntil { pagerState.currentPageOffsetFraction > 0f }
        assertThat(pagerState.currentPage).isEqualTo(0) // the premise: interrupted before the midpoint
        compose.onNodeWithTag("pager").performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(30f, 0f)) }
            up()
        }
        compose.mainClock.advanceTimeBy(5_000)

        assertThat(pagerState.currentPage).isEqualTo(0)
        assertThat(pagerState.currentPageOffsetFraction).isEqualTo(0f)
        assertThat(selected).isEqualTo(DrawerTab.Personal)
        compose.onNodeWithText("Personal").assertIsSelected()
    }

    @Test fun aSwipeToWorkStoresWorkAndHighlightsIt() {
        val pagerState = pausedTabPager()

        compose.onNodeWithTag("pager").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-25f, 0f)) }
            up()
        }
        compose.mainClock.advanceTimeBy(5_000)

        assertThat(pagerState.currentPage).isEqualTo(1)
        assertThat(selected).isEqualTo(DrawerTab.Work)
        compose.onNodeWithText("Work").assertIsSelected()
    }

    @Test fun pauseButtonInvokesWhenEnabledOnly() {
        var clicks = 0
        compose.setContent { ExpressiveTheme { PauseWorkAppsButton(enabled = false, onClick = { clicks++ }) } }

        compose.onNodeWithText("Pause work apps").assertIsNotEnabled().performClick()

        assertThat(clicks).isEqualTo(0)
    }

    @Test fun pauseButtonInvokesWhenEnabled() {
        var clicks = 0
        compose.setContent { ExpressiveTheme { PauseWorkAppsButton(enabled = true, onClick = { clicks++ }) } }

        compose.onNodeWithText("Pause work apps").performClick()

        assertThat(clicks).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h640dp")
    fun workPageReservesTheMeasuredButtonHeightUnderTheGrid() {
        // The Russian label at 200 % on a 320 dp screen makes the floating button several lines
        // tall; a fixed 72 dp reservation left the last app row under it even when scrolled to the end.
        var reserved: Dp = 0.dp
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                ExpressiveTheme {
                    Box(Modifier.width(320.dp).size(320.dp, 640.dp)) {
                        WorkPage(
                            workProfile = WorkProfileUi(paused = false, canPause = true, transitioning = false),
                            onSetWorkPaused = {},
                            grid = { bottomPadding ->
                                reserved = bottomPadding
                                Box(Modifier.fillMaxSize())
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val buttonHeight = compose.onNodeWithText("Приостановить рабочие приложения").getBoundsInRoot().height.value
        assertThat(buttonHeight).isGreaterThan(48f) // the premise: the label really wraps here
        assertThat(reserved.value).isAtLeast(buttonHeight + 16f)
    }

    @Test fun pausedCardShowsTitleBodyAndUnpause() {
        var unpauses = 0
        compose.setContent {
            ExpressiveTheme { WorkPausedCard(canPause = true, enabled = true, onUnpause = { unpauses++ }) }
        }

        compose.onNodeWithText("Work apps are paused").assertExists()
        compose.onNodeWithText("Work apps can't send notifications, use the battery or access your location.")
            .assertExists()
        compose.onNodeWithText("Unpause").performClick()

        assertThat(unpauses).isEqualTo(1)
    }

    @Test fun pausedCardHandsAPullDownToTheNestedScrollParent() {
        // The drawer's pull-to-close lives in a NestedScrollConnection above the page: the paused
        // page must dispatch a downward drag to it like the grids do, or the drawer cannot be
        // pulled shut while the profile is paused.
        var pulled = 0f
        val connection = object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) pulled += available.y
                return Offset.Zero
            }
        }
        compose.setContent {
            ExpressiveTheme {
                Box(Modifier.size(300.dp).nestedScroll(connection).testTag("host")) {
                    WorkPausedCard(canPause = true, enabled = true, onUnpause = {})
                }
            }
        }

        compose.onNodeWithTag("host").performTouchInput {
            swipe(start = Offset(150f, 40f), end = Offset(150f, 260f), durationMillis = 120)
        }
        compose.waitForIdle()

        assertThat(pulled).isGreaterThan(0f)
    }

    @Test fun pausedCardHidesUnpauseWhenItCannotPause() {
        compose.setContent {
            ExpressiveTheme { WorkPausedCard(canPause = false, enabled = true, onUnpause = {}) }
        }

        compose.onNodeWithText("Work apps are paused").assertExists()
        compose.onNodeWithText("Unpause").assertDoesNotExist()
    }
}
