package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app drawer's background colour row (a Telegram request after 0.9.9): a "Theme" chip for the theme's
 * own surface and the nine label swatches; the chosen one is marked, a tap reports the colour or null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerColorRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val picked = mutableListOf<Int?>()
    private val blue = 0xFF42A5F5.toInt()

    private fun show(selected: Int?, locked: Boolean = false) {
        compose.setContent { DrawerColorRow(selected = selected, locked = locked, onPick = { picked += it }) }
        compose.waitForIdle()
    }

    private fun transparent() = compose.onNodeWithText(res.getString(R.string.settings_color_transparent))
    private fun theme() = compose.onNodeWithText(res.getString(R.string.settings_color_theme))

    @Test fun theThemeIsTheDefaultAndASwatchPicksAColour() {
        show(selected = null)

        compose.onNodeWithText(res.getString(R.string.settings_drawer_color)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_color_theme)).assertIsSelected()
        // The strip scrolls sideways; the later swatches start off a narrow screen.
        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsNotSelected().performScrollTo().performClick()

        assertThat(picked).containsExactly(blue)
    }

    @Test fun theThemeChipClearsAChosenColour() {
        show(selected = blue)

        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsSelected()
        compose.onNodeWithText(res.getString(R.string.settings_color_theme)).assertIsNotSelected().performClick()

        assertThat(picked).containsExactly(null)
    }

    /** The owner's 1.0.0 note: "Transparent" leads the strip and means no colour layer at all, a choice of its own
     *  beside the theme's surface. */
    @Test fun theTransparentChipPicksNoColourAtAll() {
        show(selected = null)
        transparent().assertIsDisplayed().assertIsNotSelected().performClick()
        assertThat(picked).containsExactly(LauncherSettings.DRAWER_COLOR_TRANSPARENT)
    }

    @Test fun theTransparentChoiceIsMarkedAsItsOwn() {
        show(selected = LauncherSettings.DRAWER_COLOR_TRANSPARENT)
        transparent().assertIsSelected()
        theme().assertIsNotSelected()
        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsNotSelected()
    }

    /** With the user's own picture behind the drawer the colour is off: the row keeps showing the choice but takes
     *  no taps and says why, so the picture is what shows, and the colour is back once the picture goes. */
    @Test fun withAPictureTheRowIsLockedAndSaysWhy() {
        show(selected = blue, locked = true)

        compose.onNodeWithText(res.getString(R.string.settings_drawer_color_locked)).assertIsDisplayed()
        compose.onNodeWithTag(colorSwatchTag(blue)).performScrollTo().assertIsSelected().assertIsNotEnabled().performClick()
        theme().assertIsNotEnabled().performClick()
        transparent().assertIsNotEnabled().performClick()

        assertThat(picked).isEmpty()
    }

    @Test fun withoutAPictureTheRowSaysNothingAboutOne() {
        show(selected = blue)
        compose.onNodeWithText(res.getString(R.string.settings_drawer_color_locked)).assertDoesNotExist()
        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsEnabled()
    }

    @Test fun everySwatchIsOffered() {
        show(selected = null)

        COLOR_SWATCHES.forEach { compose.onNodeWithTag(colorSwatchTag(it)).performScrollTo().assertIsDisplayed() }
        assertThat(COLOR_SWATCHES).hasSize(9)
    }
}
