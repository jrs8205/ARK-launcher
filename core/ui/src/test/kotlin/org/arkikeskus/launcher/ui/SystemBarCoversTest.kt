package org.arkikeskus.launcher.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A full-screen surface over home reports what is behind the system bars while it is shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SystemBarCoversTest {
    @get:Rule val compose = createComposeRule()

    private val covers = SystemBarCovers()

    @Test fun aShownCoverDecidesUntilItLeaves() {
        var shown by mutableStateOf(true)
        var light by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalSystemBarCovers provides covers) {
                if (shown) CoverSystemBars(light = light)
            }
        }
        compose.waitForIdle()
        assertThat(covers.coverIsLight).isTrue()

        light = false
        compose.waitForIdle()
        assertThat(covers.coverIsLight).isFalse()

        shown = false
        compose.waitForIdle()
        assertThat(covers.coverIsLight).isNull()
    }

    /** The widget picker fades out while a widget is dragged onto home: home shows again. */
    @Test fun anInactiveCoverDoesNotDecide() {
        var active by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalSystemBarCovers provides covers) {
                CoverSystemBars(light = true, active = active)
            }
        }
        compose.waitForIdle()
        assertThat(covers.coverIsLight).isNull()

        active = true
        compose.waitForIdle()
        assertThat(covers.coverIsLight).isTrue()
    }

    /** A scrim a home surface draws over the whole of home (the open folder's) is no cover: home still shows,
     *  darker. It reports how dark, as it is now (it may fade), and several stack as their layers do. */
    @Test fun aScrimOverHomeReportsHowDarkItIs() {
        var shown by mutableStateOf(true)
        var alpha by mutableStateOf(0.5f)
        var second by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalSystemBarCovers provides covers) {
                if (shown) DimSystemBars { alpha }
                if (second) DimSystemBars { 0.5f }
            }
        }
        compose.waitForIdle()
        assertThat(covers.homeDim).isEqualTo(0.5f)
        assertThat(covers.coverIsLight).isNull()

        alpha = 0.2f
        assertThat(covers.homeDim).isWithin(0.0001f).of(0.2f)

        second = true
        compose.waitForIdle()
        assertThat(covers.homeDim).isWithin(0.0001f).of(0.6f)

        shown = false
        second = false
        compose.waitForIdle()
        assertThat(covers.homeDim).isEqualTo(0f)
    }

    @Test fun theLastShownCoverDecides() {
        var second by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalSystemBarCovers provides covers) {
                CoverSystemBars(light = false)
                if (second) CoverSystemBars(light = true)
            }
        }
        compose.waitForIdle()
        assertThat(covers.coverIsLight).isFalse()

        second = true
        compose.waitForIdle()
        assertThat(covers.coverIsLight).isTrue()

        second = false
        compose.waitForIdle()
        assertThat(covers.coverIsLight).isFalse()
    }

    @Test fun withoutAShellNothingIsReported() {
        compose.setContent { CoverSystemBars(light = true) }
        compose.waitForIdle()

        assertThat(covers.coverIsLight).isNull()
    }
}
