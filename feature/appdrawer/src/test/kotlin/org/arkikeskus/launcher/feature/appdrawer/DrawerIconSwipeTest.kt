package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
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
 * The drawer's icon press phase inside the new Personal / Work pager. A sideways swipe that starts
 * on an app icon belongs to the pager, exactly as on the home screen: it must turn the page, never
 * launch the app under the finger and never lift it — the pager claims the gesture in the Main
 * pass AFTER the icon, so only the Final pass shows the icon it lost the press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerIconSwipeTest {
    @get:Rule val compose = createComposeRule()

    private val app = AppItem(
        packageName = "com.mail", className = "mail.Main", user = Process.myUserHandle(), userSerial = 0, label = "Mail",
    )
    private var clicks = 0
    private var longClicks = 0
    private var longPress = 0L
    private lateinit var pager: PagerState
    private val dragController = HomeDragController()

    private fun compose() {
        compose.setContent {
            pager = rememberPagerState { 2 }
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            val haptics = LocalHapticFeedback.current
            HorizontalPager(
                state = pager,
                userScrollEnabled = !dragController.isDragging,
                modifier = Modifier.size(300.dp).testTag("pager"),
            ) { page ->
                if (page == 0) {
                    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize()) {
                        appCells(
                            apps = listOf(app), badges = emptyMap(), badgeShowCount = true, badgeScale = 1f,
                            showLabels = true, iconSize = 56.dp, onAppClick = { clicks++ }, onAppLongClick = { _, _ -> longClicks++ },
                            dragController = dragController, onDragOutStart = {}, onDropOnHome = { _, _, _, _ -> },
                            onDropOnDock = { _, _ -> }, haptics = haptics, locked = false,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
    }

    /** Issue #12: the menu opens as the icon lifts, while the finger is still down, and only once. */
    @Test fun aStillHoldOpensTheMenuBeforeTheFingerLifts() {
        compose()
        compose.waitForIdle()

        compose.onNodeWithText("Mail").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(longClicks).isEqualTo(1)

        compose.onNodeWithText("Mail").performTouchInput { up() }
        compose.waitForIdle()
        assertThat(longClicks).isEqualTo(1)
        assertThat(clicks).isEqualTo(0)
    }

    @Test fun aCleanTapOnAnIconLaunchesIt() {
        compose()

        compose.onNodeWithText("Mail").performTouchInput { click() }
        compose.waitForIdle()

        assertThat(clicks).isEqualTo(1)
        assertThat(pager.currentPage).isEqualTo(0)
    }

    /** A finger-paced swipe: small steps, so the page keeps up and the finger never leaves the icon. */
    private fun swipeLeftFromTheIcon(steps: Int, stepPx: Float, stepMs: Long) {
        compose.onNodeWithText("Mail").performTouchInput {
            down(center)
            repeat(steps) { moveBy(Offset(-stepPx, 0f), delayMillis = stepMs) }
            up()
        }
    }

    @Test fun aSidewaysFlickThatStartsOnAnIconTurnsThePageAndDoesNotLaunch() {
        compose()

        swipeLeftFromTheIcon(steps = 40, stepPx = 6f, stepMs = 4)
        compose.waitForIdle()

        assertThat(pager.currentPage).isEqualTo(1)
        assertThat(clicks).isEqualTo(0)
    }

    @Test fun aSlowSidewaysSwipeThatStartsOnAnIconNeitherLiftsNorLaunches() {
        compose()

        val steps = 60
        swipeLeftFromTheIcon(steps = steps, stepPx = 4f, stepMs = (longPress + 400) / steps)
        compose.waitForIdle()

        assertThat(dragController.draggedApp).isNull()
        assertThat(clicks).isEqualTo(0)
        assertThat(pager.currentPage).isEqualTo(1)
    }
}
