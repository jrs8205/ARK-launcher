package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * An owner decision for 1.0.0: a swipe up that starts on the dock opens the drawer as on a Pixel. The
 * dock keeps everything else: a tap launches, a still hold opens the menu and lifts the icon, a small
 * drift during the hold (the old Samsung flakiness) stays a hold, and a sideways pull is the dock's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DockSwipeUpTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private val maps = AppItem("com.maps", "maps.Main", Process.myUserHandle(), 0L, "Maps")
    private val mail = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val entries = mutableStateOf<List<DockEntry>>(listOf(DockApp(1L, maps), DockApp(2L, mail)))
    private val clicks = mutableListOf<String>()
    private val menus = mutableListOf<String>()
    private var drawerDrags = 0
    private var drawerSettles = 0
    private var shadeOpens = 0
    private var longPress = 0L
    private var touchSlop = 0f

    private fun show() {
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier
                    .size(300.dp, 400.dp)
                    .pixelHomeSwipe(
                        swipeUpForDrawer = true,
                        swipeDownForNotifications = true,
                        dragController = drag,
                        onDrawerDrag = { drawerDrags++ },
                        onDrawerSettle = { drawerSettles++ },
                        onOpenNotifications = { shadeOpens++ },
                    ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Dock(
                        entries = entries.value,
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

    @Test fun theDockSlopIsLongerAndSteeperThanHomes() {
        assertThat(dockSwipeSlop(touchSlop = 8f, minTravelPx = 20f)).isEqualTo(20f)
        assertThat(dockSwipeSlop(touchSlop = 16f, minTravelPx = 20f)).isEqualTo(32f)
        assertThat(dockSwipeIsVertical(dx = 0f, dy = -30f, slop = 20f)).isTrue()
        assertThat(dockSwipeIsVertical(dx = 0f, dy = 30f, slop = 20f)).isTrue()
        assertThat(dockSwipeIsVertical(dx = 0f, dy = -15f, slop = 20f)).isFalse() // a drift
        assertThat(dockSwipeIsVertical(dx = 20f, dy = -30f, slop = 20f)).isFalse() // too sideways
        assertThat(dockSwipeIsVertical(dx = 10f, dy = -30f, slop = 20f)).isTrue()
    }

    @Test fun aFlickUpFromADockIconDrivesTheDrawerAndTheIconLetsGo() {
        show()

        compose.onNodeWithText("Maps").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -30f)) }
        }
        compose.mainClock.advanceTimeBy(longPress + 100) // the hold would complete now: it must not
        compose.onNodeWithText("Maps").performTouchInput { up() }

        assertThat(drawerDrags).isGreaterThan(0)
        assertThat(drawerSettles).isEqualTo(1)
        assertThat(menus).isEmpty()
        assertThat(clicks).isEmpty()
        assertThat(drag.isDragging).isFalse()
    }

    @Test fun aTapOnADockIconStillLaunches() {
        show()

        compose.onNodeWithText("Mail").performTouchInput {
            down(center)
            up()
        }

        assertThat(clicks).containsExactly("Mail")
        assertThat(drawerDrags).isEqualTo(0)
    }

    /** The Samsung case: a few pixels of drift during the hold stay a hold, so the menu opens as before. */
    @Test fun aStillHoldWithADriftStillOpensTheMenuNotTheDrawer() {
        show()

        compose.onNodeWithText("Maps").performTouchInput {
            down(center)
            moveBy(Offset(0f, -4f))
            moveBy(Offset(1f, -4f))
        }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()

        assertThat(menus).containsExactly("Maps")
        assertThat(drag.isDragging).isTrue()
        assertThat(drawerDrags).isEqualTo(0)

        compose.onNodeWithText("Maps").performTouchInput { up() }
    }

    @Test fun aSidewaysPullOnTheDockIsNotTheDrawersNorTheShades() {
        show()

        compose.onNodeWithText("Maps").performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(30f, -20f)) }
            up()
        }

        assertThat(drawerDrags).isEqualTo(0)
        assertThat(shadeOpens).isEqualTo(0)
    }

    @Test fun aFlickDownFromTheDockOpensTheShade() {
        show()

        compose.onNodeWithText("Mail").performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, 30f)) }
            up()
        }

        assertThat(shadeOpens).isEqualTo(1)
        assertThat(drawerDrags).isEqualTo(0)
    }
}
