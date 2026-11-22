package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the wallpaper is told while the home pager moves, seen through a recording [WallpaperOffsets]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WallpaperScrollEffectTest {
    @get:Rule val compose = createComposeRule()

    private class Recorded(var live: Boolean = false) : WallpaperOffsets {
        val calls = mutableListOf<String>()
        val values = mutableListOf<Float>()
        val steps = mutableListOf<Float>()
        override fun isLive(): Boolean = live
        override fun set(x: Float, xStep: Float) {
            calls += "set"
            values += x
            steps += xStep
        }
        override fun clear() {
            calls += "clear"
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()
    private lateinit var pager: PagerState
    private lateinit var scope: CoroutineScope
    private var pages by mutableIntStateOf(3)
    private var copyAtTail by mutableStateOf(false)
    private var enabled by mutableStateOf<Boolean?>(true)

    /** One laid-out frame: the pager position it shows, and what the wallpaper had last been told by then. */
    private class Frame(val position: Float, val told: Float?)

    private val frames = mutableListOf<Frame>()

    private fun show(offsets: Recorded, initialPage: Int = 0) {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val state = rememberPagerState(initialPage = initialPage) { pages + 1 }
                pager = state
                scope = rememberCoroutineScope()
                HorizontalPager(state, Modifier.size(300.dp, 500.dp).testTag("pager")) { Box(Modifier.fillMaxSize()) }
                // Laid out again in every frame that moves the pager (the position is read in the layout phase).
                Box(
                    Modifier.layout { measurable, constraints ->
                        frames += Frame(state.currentPage + state.currentPageOffsetFraction, offsets.values.lastOrNull())
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                )
                WallpaperScrollEffect(state, pages, copyAtTail, enabled, offsets)
            }
        }
        compose.waitForIdle()
    }

    /** The frames that moved the pager, each with what the wallpaper had been told when it was laid out. */
    private fun movingFrames(): List<Frame> = frames.zipWithNext().filter { (a, b) -> b.position != a.position }.map { it.second }

    private fun expected(frame: Frame) = wallpaperOffsetX(frame.position, pages, copyAtTail, liveWallpaper = false)

    // The system moves the picture once per display frame, in its own time. A value that leaves only after the
    // frame's own work (its layout and drawing) leaves at a moment that changes from frame to frame, so the system
    // gets none in one of its frames and two in the next: the picture stands and then jumps while the page glides.
    // So each frame's value is sent at the frame's start, before the frame is laid out, and it is that frame's own.

    @Test fun whileAPageSettlesEachFramesOwnValueIsSentBeforeTheFrameIsLaidOut() {
        val offsets = Recorded()
        show(offsets)
        compose.mainClock.autoAdvance = false
        frames.clear()

        compose.runOnIdle { scope.launch { pager.animateScrollToPage(1) } }
        repeat(90) { compose.mainClock.advanceTimeByFrame() }

        assertThat(pager.currentPage).isEqualTo(1)
        val moved = movingFrames()
        assertThat(moved.size).isGreaterThan(5)
        for (frame in moved) {
            assertWithMessage("at position ${frame.position}").that(frame.told).isWithin(1e-4f).of(expected(frame))
        }
    }

    @Test fun underAFingerEachFramesOwnValueIsSentBeforeTheFrameIsLaidOutToo() {
        val offsets = Recorded()
        show(offsets)
        compose.mainClock.autoAdvance = false
        frames.clear()

        compose.onNodeWithTag("pager").performTouchInput {
            down(center)
            repeat(12) {
                moveBy(Offset(-12f, 0f))
                advanceEventTime(16)
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }

        // The first frames of a drag are the gesture finding out that it is one: from the fourth on.
        val moved = movingFrames().drop(3)
        assertThat(moved.size).isGreaterThan(4)
        for (frame in moved) {
            assertWithMessage("at position ${frame.position}").that(frame.told).isWithin(1e-4f).of(expected(frame))
        }
        compose.onNodeWithTag("pager").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun atRestNoFrameIsAskedForAndNothingIsSent() {
        val offsets = Recorded()
        show(offsets)
        turnTo(1)
        val sent = offsets.values.size

        // A loop that asked for every frame would never let the screen come to rest (and would never let this
        // wait end): the wallpaper is told again only when something moves.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()

        assertThat(offsets.values.size).isEqualTo(sent)
    }

    @Test fun oneValueIsSentPerFrameThatMoves() {
        val offsets = Recorded()
        show(offsets)
        compose.mainClock.autoAdvance = false
        frames.clear()
        val before = offsets.values.size

        compose.runOnIdle { scope.launch { pager.animateScrollToPage(1) } }
        repeat(90) { compose.mainClock.advanceTimeByFrame() }

        val sent = offsets.values.drop(before)
        // Never the same value twice in a row, and no more values than frames that moved (one more for the rest).
        assertThat(sent.zipWithNext().none { (a, b) -> a == b }).isTrue()
        assertThat(sent.size).isAtMost(movingFrames().size + 1)
    }

    private fun turnTo(page: Int) {
        compose.runOnIdle { scope.launch { pager.animateScrollToPage(page) } }
        compose.waitForIdle()
        assertThat(pager.currentPage).isEqualTo(page)
    }

    private fun largestStep(values: List<Float>): Float =
        values.zipWithNext { a, b -> kotlin.math.abs(b - a) }.maxOrNull() ?: 0f

    @Test fun turningAPageSendsRisingValuesUpToThePagesOwn() {
        val offsets = Recorded()
        show(offsets)
        assertThat(offsets.values.last()).isWithin(1e-4f).of(0f)

        turnTo(1)

        assertThat(offsets.values.zipWithNext().all { (a, b) -> b >= a }).isTrue()
        assertThat(offsets.values.size).isGreaterThan(3) // frame by frame, not one jump
        assertThat(offsets.values.last()).isWithin(1e-4f).of(1f / 3)
        assertThat(offsets.steps.toSet()).containsExactly(1f / 3)
        assertThat(offsets.calls).doesNotContain("clear")
    }

    @Test fun theWrapEndsAtTheStart() {
        copyAtTail = true
        val offsets = Recorded()
        show(offsets, initialPage = 2)
        assertThat(offsets.values.last()).isWithin(1e-4f).of(2f / 3)

        turnTo(3) // onto the copy of page 0

        assertThat(offsets.values.last()).isWithin(1e-4f).of(0f)
        assertThat(largestStep(offsets.values)).isLessThan(0.3f)
    }

    @Test fun switchedOffItClearsOnceAndSendsNothing() {
        enabled = false
        val offsets = Recorded()
        show(offsets)
        turnTo(1)
        assertThat(offsets.calls).containsExactly("clear")
    }

    @Test fun turnedOffAfterBeingOnItClearsOnceMore() {
        val offsets = Recorded()
        show(offsets)
        turnTo(1)
        val before = offsets.calls.size

        enabled = false
        compose.waitForIdle()
        turnTo(2)

        assertThat(offsets.calls.drop(before)).containsExactly("clear")
    }

    @Test fun nothingIsSentUntilTheChoiceIsKnown() {
        enabled = null
        val offsets = Recorded()
        show(offsets)
        turnTo(1)
        assertThat(offsets.calls).isEmpty()
    }

    @Test fun aPageCountChangeGlides() {
        pages = 6
        val offsets = Recorded()
        show(offsets, initialPage = 2)
        assertThat(offsets.values.last()).isWithin(1e-4f).of(0.4f)
        val before = offsets.values.size

        pages = 5 // a page was removed elsewhere: page 2 now stands at 2 / 4
        compose.waitForIdle()

        val glide = offsets.values.drop(before - 1)
        assertThat(glide.last()).isWithin(1e-4f).of(0.5f)
        assertThat(glide.size).isGreaterThan(3)
        assertThat(largestStep(glide)).isLessThan(0.05f)
    }

    @Test fun aLiveWallpaperIsAskedAgainOnResume() {
        val offsets = Recorded()
        show(offsets, initialPage = 1)
        assertThat(offsets.values.last()).isWithin(1e-4f).of(1f / 3)

        offsets.live = true
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.waitForIdle()

        assertThat(offsets.values.last()).isWithin(1e-4f).of(0.5f)
        assertThat(offsets.steps.last()).isWithin(1e-4f).of(0.5f)
    }

    @Test fun comingBackSendsTheCurrentValueAgain() {
        val offsets = Recorded()
        show(offsets, initialPage = 1)
        val before = offsets.values.size

        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.waitForIdle()

        assertThat(offsets.values.size).isGreaterThan(before)
        assertThat(offsets.values.last()).isWithin(1e-4f).of(1f / 3)
    }
}
