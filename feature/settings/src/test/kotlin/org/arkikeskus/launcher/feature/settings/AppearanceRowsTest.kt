package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
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
 * The rows of the 1.0.5 appearance settings: a card colour (the open folder's, the clock/weather
 * widget's) is the theme's own until a swatch is picked, and the weather icon is one of four styles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppearanceRowsTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val blue = 0xFF42A5F5.toInt()

    @Test fun aCardColourIsTheThemesUntilASwatchIsPicked() {
        val picked = mutableListOf<Int?>()
        compose.setContent {
            CardColorRow(title = res.getString(R.string.settings_folder_color), selected = null, onPick = { picked += it })
        }
        compose.waitForIdle()

        compose.onNodeWithText(res.getString(R.string.settings_folder_color)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_color_theme)).assertIsSelected()
        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsNotSelected().performScrollTo().performClick()

        assertThat(picked).containsExactly(blue)
    }

    @Test fun theThemeChipClearsACardColour() {
        val picked = mutableListOf<Int?>()
        compose.setContent {
            CardColorRow(title = res.getString(R.string.settings_clock_widget_color), selected = blue, onPick = { picked += it })
        }
        compose.waitForIdle()

        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsSelected()
        compose.onNodeWithText(res.getString(R.string.settings_color_theme)).assertIsNotSelected().performClick()

        assertThat(picked).containsExactly(null)
    }

    @Test fun theWeatherIconRowOffersTheThreeStyles() {
        val picked = mutableListOf<String>()
        compose.setContent { WeatherIconRow(selected = LauncherSettings.WEATHER_ICON_EMOJI, onPick = { picked += it }) }
        compose.waitForIdle()

        compose.onNodeWithText(res.getString(R.string.settings_weather_icon)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_weather_icon_standard)).assertIsSelected()
        compose.onNodeWithText(res.getString(R.string.settings_weather_icon_color)).assertIsNotSelected().performScrollTo().performClick()
        compose.onNodeWithText(res.getString(R.string.settings_weather_icon_animated)).performScrollTo().performClick()
        // The size is no style: it has a row of its own.
        compose.onNodeWithText(res.getString(R.string.settings_weather_icon_large)).assertDoesNotExist()

        assertThat(picked).containsExactly(LauncherSettings.WEATHER_ICON_COLOR, LauncherSettings.WEATHER_ICON_ANIMATED).inOrder()
    }

    /** The size beside the style (the owner's note: a moving icon that is large too). */
    @Test fun theWeatherIconSizeRowOffersStandardAndLarge() {
        val picked = mutableListOf<Boolean>()
        compose.setContent { WeatherIconSizeRow(large = false, onPick = { picked += it }) }
        compose.waitForIdle()

        compose.onNodeWithText(res.getString(R.string.settings_weather_icon_size)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_weather_icon_standard)).assertIsSelected()
        compose.onNodeWithText(res.getString(R.string.settings_weather_icon_large)).assertIsNotSelected().performClick()
        compose.onNodeWithText(res.getString(R.string.settings_weather_icon_standard)).performClick()

        assertThat(picked).containsExactly(true, false).inOrder()
    }
}
