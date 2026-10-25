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
