package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A Telegram request after 1.0.4 (swipe actions in more than one direction): a swipe DOWN that starts
 * on a home app icon opens the app chosen for it in Edit ("Swipe down opens") instead of the
 * notification shade, beside the swipe-up choice. And the travel a swipe from an icon needs before
 * its own action fires follows the "Icon swipe distance" setting (a request to make swipes from
 * folders less sensitive), while the drawer and the shade keep the usual slop everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppSwipeDownTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val chat = AppItem("com.chat", "chat.Main", Process.myUserHandle(), 0L, "Chat")
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val entries = listOf(PlacedApp(mail, page = 0, cellX = 1, cellY = 2), PlacedApp(chat, page = 0, cellX = 2, cellY = 4))
    private val launchedUp = mutableListOf<AppItem>()
    private val launchedDown = mutableListOf<AppItem>()
    private val clicked = mutableListOf<AppItem>()
    private var drawerDrags = 0
    private var shadeOpens = 0
    private var iconSwipeDistance by mutableFloatStateOf(1f)
    private var pages = 1
    private val touchSlop get() = android.view.ViewConfiguration.get(RuntimeEnvironment.getApplication()).scaledTouchSlop.toFloat()

    private fun show(
        up: Map<String, AppItem> = emptyMap(),
        down: Map<String, AppItem> = mapOf(mail.key to maps),
        distance: Float = 1f,
    ) {
        iconSwipeDistance = distance
        compose.setContent {
            Box(
                Modifier
                    .size(300.dp, 400.dp)
                    .pixelHomeSwipe(
                        swipeUpForDrawer = true,
                        swipeDownForNotifications = true,
                        dragController = drag,
                        onDrawerDrag = { drawerDrags++ },
                        onDrawerSettle = {},
                        onOpenNotifications = { shadeOpens++ },
                        appSwipeUpTargets = up,
                        onAppSwipeUp = { launchedUp += it },
                        appSwipeDownTargets = down,
                        onAppSwipeDown = { launchedDown += it },
                        iconSwipeDistance = iconSwipeDistance,
                    ),
            ) {
                Workspace(
                    pageCount = pages, columns = 4, rows = 6, entries = entries,
                    badges = emptyMap(), badgeShowCount = false, badgeScale = 1f, showLabels = true, showPageIndicator = true,
                    locked = false, homeSignals = emptyFlow(), homePage = 0, dragController = drag,
                    widgetDragController = remember { WidgetDragController(drag) },
                    onAddWidget = { _, _ -> }, onAppClick = { clicked += it }, onAppMenu = { _, _, _ -> },
                    onMove = { _, _, _, _ -> true }, onMoveFolder = { _, _, _, _ -> true }, onDropOnDock = { _, _ -> },
                    onRemoveFromHome = {}, onOpenFolder = {}, onLaunchShortcut = {}, onRemoveShortcut = {},
                    onCreateFolder = { _, _ -> }, onAddToFolder = { _, _ -> }, onEmptyAreaMenu = { _, _ -> },
                    modifier = Modifier.size(300.dp, 400.dp),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun swipeDownOn(label: String) {
        compose.onNodeWithText(label).performTouchInput {
            swipe(start = Offset(width / 2f, 2f), end = Offset(width / 2f, height * 3f), durationMillis = 100)
        }
        compose.waitForIdle()
    }

    private fun swipeUpOn(label: String) {
        compose.onNodeWithText(label).performTouchInput {
            swipe(start = Offset(width / 2f, height - 2f), end = Offset(width / 2f, -height * 2f), durationMillis = 100)
        }
        compose.waitForIdle()
    }

    /** One finger down on [label]'s icon, moved [dy] px in one step and held there, then released. */
    private fun pull(label: String, dy: Float) {
        val start = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            moveBy(Offset(0f, dy))
            up()
        }
        compose.waitForIdle()
    }

    @Test fun aSwipeDownOnAnIconWithAChoiceOpensThatAppNotTheShade() {
        show()

        swipeDownOn("Mail")

        assertThat(launchedDown).containsExactly(maps)
        assertThat(shadeOpens).isEqualTo(0)
        assertThat(launchedUp).isEmpty()
    }

    @Test fun aSwipeDownOnAnIconWithoutAChoiceStillOpensTheShade() {
        show()

        swipeDownOn("Chat")

        assertThat(launchedDown).isEmpty()
        assertThat(shadeOpens).isEqualTo(1)
    }

    @Test fun theTwoDirectionsHaveTheirOwnChoices() {
        show(up = mapOf(mail.key to chat), down = mapOf(mail.key to maps))

        swipeUpOn("Mail")
        assertThat(launchedUp).containsExactly(chat)
        assertThat(launchedDown).isEmpty()

        swipeDownOn("Mail")
        assertThat(launchedDown).containsExactly(maps)
        assertThat(drawerDrags).isEqualTo(0)
        assertThat(shadeOpens).isEqualTo(0)
    }

    /** A down choice alone leaves the icon's swipe up to the drawer. */
    @Test fun aDownChoiceAloneKeepsTheDrawerOnTheWayUp() {
        show()

        swipeUpOn("Mail")

        assertThat(launchedDown).isEmpty()
        assertThat(drawerDrags).isGreaterThan(0)
    }

    /** "Icon swipe distance": at 100 % a pull of twice the slop fires the icon's action; at 400 % the
     *  same pull does nothing at all (not the shade either), and a pull past four slops fires it. */
    @Test fun theIconSwipeDistanceSettingLengthensThePullAnIconNeeds() {
        show(distance = 1f)
        pull("Mail", 2f * touchSlop)
        assertThat(launchedDown).containsExactly(maps)

        launchedDown.clear()
        iconSwipeDistance = 4f
        compose.waitForIdle()
        pull("Mail", 2f * touchSlop)
        assertThat(launchedDown).isEmpty()
        assertThat(shadeOpens).isEqualTo(0)

        pull("Mail", 4f * touchSlop + 2f)
        assertThat(launchedDown).containsExactly(maps)
    }

    /** A pull that stops short of the distance and ends on the icon is a tap, like any press that rises on
     *  it with nothing else having taken it: the sloppy tap the longer distance is for opens the icon (or
     *  the folder), not the swipe's app and not nothing. */
    @Test fun aPullShortOfTheDistanceThatEndsOnTheIconIsATap() {
        show(up = mapOf(mail.key to maps), down = emptyMap(), distance = 4f)

        // Upwards from the label, so the finger stays on the tile (downwards it would leave it).
        pull("Mail", -2f * touchSlop)

        assertThat(clicked).containsExactly(mail)
        assertThat(launchedUp).isEmpty()
        assertThat(drawerDrags).isEqualTo(0)
    }

    /** The setting is about icons with an action of their own: elsewhere the usual slop still applies. */
    @Test fun theIconSwipeDistanceLeavesIconsWithoutAnActionAlone() {
        show(distance = 4f)

        pull("Chat", 2f * touchSlop)

        assertThat(shadeOpens).isEqualTo(1)
    }

    /** With a long icon swipe distance a diagonal pull crosses the pager's sideways slop while the icon's
     *  action still waits for its travel: the page turn has the gesture then, and going on upwards must
     *  not open the icon's app on top of it. */
    @Test fun aPullThePagerTookNeverFiresTheIconsActionAfterwards() {
        pages = 2
        show(up = mapOf(mail.key to maps), down = emptyMap(), distance = 4f)
        val start = compose.onNodeWithText("Mail").fetchSemanticsNode().boundsInRoot.center

        compose.onRoot().performTouchInput {
            down(start)
            moveBy(Offset(-2f * touchSlop, -3f * touchSlop))
            moveBy(Offset(-0.1f * touchSlop, -1.5f * touchSlop))
            moveBy(Offset(0f, -1.5f * touchSlop))
            up()
        }
        compose.waitForIdle()

        assertThat(launchedUp).isEmpty()
        assertThat(drawerDrags).isEqualTo(0)
    }

    /** Straight up, the same long pull still opens the app with two pages. */
    @Test fun aStraightLongPullStillFiresWithSeveralPages() {
        pages = 2
        show(up = mapOf(mail.key to maps), down = emptyMap(), distance = 4f)
        val start = compose.onNodeWithText("Mail").fetchSemanticsNode().boundsInRoot.center

        compose.onRoot().performTouchInput {
            down(start)
            moveBy(Offset(0f, -3f * touchSlop))
            moveBy(Offset(0f, -1.5f * touchSlop))
            up()
        }
        compose.waitForIdle()

        assertThat(launchedUp).containsExactly(maps)
    }

    @Test fun theSlopOfAnIconSwipe() {
        assertThat(iconSwipeSlop(baseSlop = 8f, touchSlop = 8f, distance = 1f)).isEqualTo(8f)
        assertThat(iconSwipeSlop(baseSlop = 8f, touchSlop = 8f, distance = 2.5f)).isEqualTo(20f)
        // The dock's own longer slop stays the floor there.
        assertThat(iconSwipeSlop(baseSlop = 20f, touchSlop = 8f, distance = 1f)).isEqualTo(20f)
        assertThat(iconSwipeSlop(baseSlop = 20f, touchSlop = 8f, distance = 4f)).isEqualTo(32f)
    }
}
