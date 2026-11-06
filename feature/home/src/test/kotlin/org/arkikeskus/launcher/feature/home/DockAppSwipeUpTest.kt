package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Swipe up opens" choice of an app holds on the dock too: a clear flick up from a dock icon
 * with a choice opens that app (the dock's own, steeper slop still applies); without one the flick
 * drives the drawer as before, and a tap or a still hold are the dock's own as ever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DockAppSwipeUpTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val chat = AppItem("com.chat", "chat.Main", Process.myUserHandle(), 0L, "Chat")
    private val launched = mutableListOf<AppItem>()
    private val clicks = mutableListOf<String>()
    private val menus = mutableListOf<String>()
    private var drawerDrags = 0
    private var longPress = 0L

    private fun show() {
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            Box(
                Modifier
                    .size(300.dp, 400.dp)
                    .pixelHomeSwipe(
                        swipeUpForDrawer = true,
                        swipeDownForNotifications = true,
                        dragController = drag,
                        onDrawerDrag = { drawerDrags++ },
                        onDrawerSettle = {},
                        onOpenNotifications = {},
                        appSwipeUpTargets = mapOf(maps.key to chat),
                        onAppSwipeUp = { launched += it },
                    ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Dock(
                        entries = listOf(DockApp(1L, maps), DockApp(2L, mail)),
                        badges = emptyMap(),
                        badgeShowCount = false,
                        badgeScale = 1f,
                        showLabels = true,
                        backgroundAlpha = 1f,
                        locked = false,
                        dragController = drag,
                        onAppClick = { clicks += it.label },
                        onReorder = {},
                        onMoveToHome = { _, _, _, _ -> },
                        onAppMenu = { app, _ -> menus += app.label },
                        modifier = Modifier.size(300.dp, 100.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun flickUpOn(label: String) {
        compose.onNodeWithText(label).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -30f)) }
            up()
        }
        compose.waitForIdle()
    }

    @Test fun aFlickUpFromADockIconWithAChoiceOpensThatApp() {
        show()

        flickUpOn("Maps")

        assertThat(launched).containsExactly(chat)
        assertThat(drawerDrags).isEqualTo(0)
        assertThat(clicks).isEmpty()
        assertThat(menus).isEmpty()
        assertThat(drag.isDragging).isFalse()
    }

    /** A flick that starts at the top edge of the tile leaves the icon's bounds (the tile lets the press
     *  go) before the dock's longer slop is crossed: the choice must still hold, so the root remembers
     *  the icon the finger went down on instead of asking at the slop. */
    @Test fun aFlickFromTheTopEdgeOfTheTileStillOpensTheChosenApp() {
        show()
        val tile = drag.dockSlots[0] // the Maps slot, as the dock publishes it (root coordinates)

        compose.onRoot().performTouchInput {
            down(Offset(tile.center.x, tile.top + 1f))
            repeat(5) { moveBy(Offset(0f, -12f)) }
            up()
        }
        compose.waitForIdle()

        assertThat(launched).containsExactly(chat)
        assertThat(drawerDrags).isEqualTo(0)
    }

    @Test fun aFlickUpFromADockIconWithoutAChoiceStillDrivesTheDrawer() {
        show()

        flickUpOn("Mail")

        assertThat(launched).isEmpty()
        assertThat(drawerDrags).isGreaterThan(0)
    }

    @Test fun aTapAndAStillHoldOnTheIconStayTheDocksOwn() {
        show()

        compose.onNodeWithText("Maps").performTouchInput {
            down(center)
            up()
        }
        assertThat(clicks).containsExactly("Maps")
        assertThat(launched).isEmpty()

        compose.onNodeWithText("Maps").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(menus).containsExactly("Maps")
        assertThat(launched).isEmpty()
        compose.onNodeWithText("Maps").performTouchInput { up() }
    }
}
