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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
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

    private fun show(offsets: Recorded, initialPage: Int = 0) {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val state = rememberPagerState(initialPage = initialPage) { pages + 1 }
                pager = state
                scope = rememberCoroutineScope()
                HorizontalPager(state, Modifier.size(300.dp, 500.dp)) { Box(Modifier.fillMaxSize()) }
                WallpaperScrollEffect(state, pages, copyAtTail, enabled, offsets)
            }
        }
        compose.waitForIdle()
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
