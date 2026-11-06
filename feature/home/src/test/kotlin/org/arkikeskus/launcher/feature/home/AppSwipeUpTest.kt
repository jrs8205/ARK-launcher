package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Telegram request after 1.0.2 (Nova-style swipe actions): a swipe up that starts on a home app
 * icon opens the app chosen for it in Edit ("Swipe up opens"), instead of the drawer. An icon
 * without a choice keeps the drawer swipe; taps and swipes down are untouched either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppSwipeUpTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val chat = AppItem("com.chat", "chat.Main", Process.myUserHandle(), 0L, "Chat")
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val entries = listOf(PlacedApp(mail, page = 0, cellX = 1, cellY = 2), PlacedApp(chat, page = 0, cellX = 2, cellY = 4))
    private val launched = mutableListOf<AppItem>()
    private val clicked = mutableListOf<AppItem>()
    private var drawerDrags = 0
    private var shadeOpens = 0

    private fun show(targets: Map<String, AppItem> = mapOf(mail.key to maps)) {
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
                        appSwipeUpTargets = targets,
                        onAppSwipeUp = { launched += it },
                    ),
            ) {
                Workspace(
                    pageCount = 1, columns = 4, rows = 6, entries = entries,
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

    private fun swipeUpOn(label: String) {
        compose.onNodeWithText(label).performTouchInput {
            swipe(start = Offset(width / 2f, height - 2f), end = Offset(width / 2f, -height * 2f), durationMillis = 100)
        }
        compose.waitForIdle()
    }

    @Test fun aSwipeUpOnAnIconWithAChoiceOpensThatAppNotTheDrawer() {
        show()

        swipeUpOn("Mail")

        assertThat(launched).containsExactly(maps)
        assertThat(drawerDrags).isEqualTo(0)
        assertThat(clicked).isEmpty() // not a tap either
    }

    @Test fun aSwipeUpOnAnIconWithoutAChoiceDrivesTheDrawer() {
        show()

        swipeUpOn("Chat")

        assertThat(launched).isEmpty()
        assertThat(drawerDrags).isGreaterThan(0)
        assertThat(clicked).isEmpty()
    }

    @Test fun withNoChoicesAtAllEveryIconDrivesTheDrawer() {
        show(targets = emptyMap())

        swipeUpOn("Mail")

        assertThat(launched).isEmpty()
        assertThat(drawerDrags).isGreaterThan(0)
    }

    @Test fun aSwipeDownStillOpensTheShadeAndATapStillLaunchesTheIconsOwnApp() {
        show()

        compose.onNodeWithText("Mail").performTouchInput {
            swipe(start = Offset(width / 2f, 2f), end = Offset(width / 2f, height * 3f), durationMillis = 100)
        }
        compose.waitForIdle()
        assertThat(shadeOpens).isEqualTo(1)
        assertThat(launched).isEmpty()

        compose.onNodeWithText("Mail").performTouchInput { click() }
        compose.waitForIdle()
        assertThat(clicked).containsExactly(mail)
        assertThat(launched).isEmpty()
    }

    /** The icon is the one under the finger that swipes: a second finger resting on another icon
     *  must not redirect (or cancel) the first finger's swipe. */
    @Test fun theIconIsTheOneUnderTheFingerThatSwipes() {
        show(targets = mapOf(mail.key to maps, chat.key to mail))
        val a = compose.onNodeWithText("Mail").fetchSemanticsNode().boundsInRoot.center
        val b = compose.onNodeWithText("Chat").fetchSemanticsNode().boundsInRoot.center

        compose.onRoot().performTouchInput {
            down(0, a)
            down(1, b)
            repeat(8) { moveBy(0, Offset(0f, -40f)) }
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertThat(launched).containsExactly(maps)
    }

    /** The decision itself: an icon's own choice wins over the folder setting and the drawer. */
    @Test fun theSwipeUpTargetRuleWithAnAppChoice() {
        assertThat(swipeUpTarget(folderUnderFinger = false, folderSwipeOpensFirstApp = false, swipeUpForDrawer = true, appChoiceUnderFinger = true)).isEqualTo(SwipeUpTarget.APP_CHOICE)
        assertThat(swipeUpTarget(folderUnderFinger = false, folderSwipeOpensFirstApp = true, swipeUpForDrawer = false, appChoiceUnderFinger = true)).isEqualTo(SwipeUpTarget.APP_CHOICE)
        assertThat(swipeUpTarget(folderUnderFinger = false, folderSwipeOpensFirstApp = false, swipeUpForDrawer = true, appChoiceUnderFinger = false)).isEqualTo(SwipeUpTarget.DRAWER)
        assertThat(swipeUpTarget(folderUnderFinger = false, folderSwipeOpensFirstApp = false, swipeUpForDrawer = false, appChoiceUnderFinger = false)).isEqualTo(SwipeUpTarget.NONE)
    }
}
