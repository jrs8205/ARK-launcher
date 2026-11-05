package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gesture navigation (a Telegram report on 1.0.1): the system's own swipe up from the bottom edge, the
 * one that opens the recent apps, reached the home swipe detector first on the reporter's phone and
 * opened the drawer (or a folder's first app) underneath the overview. Two rules keep the system's
 * gesture the system's:
 *
 * - a swipe that starts inside the bottom gesture zone (the mandatory system-gesture inset) is never
 *   the drawer's, the shade's nor a folder's;
 * - a drawer drag the system takes over (ACTION_CANCEL, delivered as an already-consumed release)
 *   closes the drawer again instead of settling by the fling's velocity, which flung it fully open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SystemGestureSwipeTest {
    @get:Rule val compose = createComposeRule()

    private val drag = HomeDragController()
    private var drawerDrags = 0
    private val settles = mutableListOf<Float>()
    private var cancels = 0
    private var shadeOpens = 0

    private fun show(bottomGestureZonePx: Float) {
        compose.setContent {
            Box(
                Modifier
                    .size(300.dp, 400.dp)
                    .testTag("home")
                    .pixelHomeSwipe(
                        swipeUpForDrawer = true,
                        swipeDownForNotifications = true,
                        dragController = drag,
                        onDrawerDrag = { drawerDrags++ },
                        onDrawerSettle = { settles += it },
                        onOpenNotifications = { shadeOpens++ },
                        bottomGestureZonePx = bottomGestureZonePx,
                        onDrawerCancel = { cancels++ },
                    ),
            )
        }
        compose.waitForIdle()
    }

    @Test fun aSwipeUpThatStartsInTheSystemsBottomGestureZoneIsNotTheDrawers() {
        show(bottomGestureZonePx = 40f)

        compose.onNodeWithTag("home").performTouchInput {
            down(Offset(width / 2f, height - 10f))
            repeat(8) { moveBy(Offset(0f, -30f)) }
            up()
        }

        assertThat(drawerDrags).isEqualTo(0)
        assertThat(settles).isEmpty()
        assertThat(cancels).isEqualTo(0)
    }

    @Test fun aSwipeUpThatStartsJustAboveTheZoneStillDrivesTheDrawer() {
        show(bottomGestureZonePx = 40f)

        compose.onNodeWithTag("home").performTouchInput {
            down(Offset(width / 2f, height - 50f))
            repeat(8) { moveBy(Offset(0f, -30f)) }
            up()
        }

        assertThat(drawerDrags).isGreaterThan(0)
        assertThat(settles).hasSize(1)
    }

    @Test fun aSwipeDownThatStartsInTheZoneDoesNotOpenTheShade() {
        show(bottomGestureZonePx = 40f)

        compose.onNodeWithTag("home").performTouchInput {
            down(Offset(width / 2f, height - 10f))
            repeat(4) { moveBy(Offset(0f, 30f)) }
            up()
        }

        assertThat(shadeOpens).isEqualTo(0)
    }

    @Test fun aDrawerDragTheSystemCancelsClosesTheDrawerInsteadOfFlingingItOpen() {
        show(bottomGestureZonePx = 0f)

        compose.onNodeWithTag("home").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -30f)) }
            cancel()
        }

        assertThat(drawerDrags).isGreaterThan(0)
        assertThat(cancels).isEqualTo(1)
        assertThat(settles).isEmpty()
    }

    @Test fun theBottomGestureZoneRule() {
        assertThat(startsInBottomGestureZone(y = 390f, height = 400, zonePx = 40f)).isTrue()
        assertThat(startsInBottomGestureZone(y = 360f, height = 400, zonePx = 40f)).isTrue()
        assertThat(startsInBottomGestureZone(y = 359f, height = 400, zonePx = 40f)).isFalse()
        assertThat(startsInBottomGestureZone(y = 399f, height = 400, zonePx = 0f)).isFalse()
    }
}
