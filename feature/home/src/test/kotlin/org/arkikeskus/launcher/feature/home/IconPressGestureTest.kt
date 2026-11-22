package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.ui.IconPressOutcome
import org.arkikeskus.launcher.ui.awaitIconPress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The press phase shared by home icons, folders and shortcuts ([awaitIconPress]): what counts as
 * a tap, when the press yields to a parent that took the gesture (the root swipe-up detector in
 * the Initial pass, the pager past its slop), and when a still hold completes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IconPressGestureTest {
    @get:Rule val compose = createComposeRule()

    private val outcomes = mutableListOf<IconPressOutcome>()

    /** A home-icon stand-in: claims the DOWN like the real icons, then runs the shared press phase. */
    @Composable
    private fun Icon(tag: String = "icon") {
        Box(
            Modifier.size(80.dp).testTag(tag).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    outcomes += awaitIconPress(down)
                }
            },
        )
    }

    /** Mirrors `pixelHomeSwipe`: the root consumes vertical moves past the slop in the Initial pass. */
    private fun Modifier.rootSwipeUp(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var claimed = false
            while (true) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                if (claimed || abs(c.position.y - down.position.y) > viewConfiguration.touchSlop) {
                    claimed = true
                    c.consume()
                }
            }
        }
    }

    @Test
    fun aCleanTapIsATap() {
        compose.setContent { Box(Modifier.size(300.dp)) { Icon() } }
        compose.onNodeWithTag("icon").performTouchInput { click() }
        compose.waitForIdle()
        assertThat(outcomes).containsExactly(IconPressOutcome.Tap)
    }

    @Test
    fun aShortFlickUpThatTheRootClaimsIsNotATapEvenWhenItLiftsOnTheIcon() {
        // The Fairphone/Pixel bug: a quick swipe-up that starts on an icon and lifts inside the
        // icon's cell opened the drawer AND launched the app underneath.
        compose.setContent {
            Box(Modifier.size(300.dp).rootSwipeUp(), contentAlignment = Alignment.Center) { Icon() }
        }
        compose.onNodeWithTag("icon").performTouchInput {
            swipe(start = Offset(width / 2f, height - 2f), end = Offset(width / 2f, 2f), durationMillis = 80)
        }
        compose.waitForIdle()
        assertThat(outcomes).containsExactly(IconPressOutcome.Cancelled)
    }

    @Test
    fun aSidewaysSwipeThatStartsOnAnIconTurnsThePageInsteadOfTapping() {
        lateinit var pager: PagerState
        compose.setContent {
            pager = rememberPagerState { 2 }
            HorizontalPager(pager, Modifier.size(300.dp).testTag("pager")) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon("icon$page") }
            }
        }
        compose.onNodeWithTag("icon0").performTouchInput {
            swipe(start = Offset(width - 2f, height / 2f), end = Offset(-width * 3f, height / 2f), durationMillis = 100)
        }
        compose.waitForIdle()
        assertThat(pager.currentPage).isEqualTo(1)
        assertThat(outcomes).containsExactly(IconPressOutcome.Cancelled)
    }

    @Test
    fun liftingOffTheIconIsNotATap() {
        compose.setContent { Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) { Icon() } }
        compose.onNodeWithTag("icon").performTouchInput {
            swipe(start = center, end = Offset(width / 2f, height + 100f), durationMillis = 80)
        }
        compose.waitForIdle()
        assertThat(outcomes).containsExactly(IconPressOutcome.Cancelled)
    }

    @Test
    fun aStillHoldCompletesWhereTheFingerIs() {
        var longPress = 0L
        compose.setContent {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) { Icon() }
        }
        compose.onNodeWithTag("icon").performTouchInput { down(Offset(30f, 40f)) }
        compose.mainClock.advanceTimeBy(longPress + 50)
        compose.waitForIdle()
        assertThat(outcomes).containsExactly(IconPressOutcome.Held(Offset(30f, 40f)))
    }

    @Test
    fun aShortSidewaysFlickInsideTheIconTurnsThePageAndDoesNotTap() {
        // The sideways twin of the swipe-up bug: a quick flick that never leaves the icon's cell
        // launched the app and left the page where it was.
        lateinit var pager: PagerState
        compose.setContent {
            pager = rememberPagerState { 2 }
            HorizontalPager(pager, Modifier.size(300.dp).testTag("pager")) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon("icon$page") }
            }
        }
        compose.onNodeWithTag("icon0").performTouchInput {
            swipe(start = Offset(width - 2f, height / 2f), end = Offset(10f, height / 2f), durationMillis = 60)
        }
        compose.waitForIdle()
        assertThat(outcomes).containsExactly(IconPressOutcome.Cancelled)
        assertThat(pager.currentPage).isEqualTo(1)
    }
}
