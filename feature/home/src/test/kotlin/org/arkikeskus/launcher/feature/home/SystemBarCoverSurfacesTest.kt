package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.LocalSystemBarCovers
import org.arkikeskus.launcher.ui.SystemBarCovers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #11, review: the widget picker and the first-run intro cover home edge to edge, behind both
 * system bars. The glyph colour must follow them, not the wallpaper they hide — a light wallpaper in
 * dark mode left dark glyphs on the dark picker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SystemBarCoverSurfacesTest {
    @get:Rule val compose = createComposeRule()

    private val covers = SystemBarCovers()

    private fun showPicker(dark: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalSystemBarCovers provides covers) {
                LauncherTheme(darkTheme = dark) {
                    WidgetPickerScreen(
                        dragController = WidgetDragController(HomeDragController()),
                        columns = 4,
                        rows = 6,
                        onPick = { _, _, _ -> },
                        onDismiss = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun theDarkWidgetPickerCoversTheBarsDark() {
        showPicker(dark = true)

        assertThat(covers.coverIsLight).isFalse()
    }

    @Test fun theLightWidgetPickerCoversTheBarsLight() {
        showPicker(dark = false)

        assertThat(covers.coverIsLight).isTrue()
    }

    @Test fun theFirstRunIntroCoversTheBarsDark() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalSystemBarCovers provides covers) {
                LauncherTheme(darkTheme = false) {
                    OnboardingFlow(onFinish = {}, onContactsGranted = {}, onPhoneGranted = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        assertThat(covers.coverIsLight).isFalse()
    }
}
