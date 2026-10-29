package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings > Home > "Tap on weather opens": the row names the chosen app (or None) and a tap on it
 * opens the app picker. It sits under the weather switch, so it is only shown while weather is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WeatherAppRowTest {
    @get:Rule val compose = createComposeRule()
    private val res = RuntimeEnvironment.getApplication().resources
    private var clicks = 0

    private fun show(appLabel: String?) {
        compose.setContent { WeatherAppRow(appLabel = appLabel) { clicks++ } }
        compose.waitForIdle()
    }

    @Test fun withoutAChosenAppTheRowSaysNone() {
        show(appLabel = null)
        compose.onNodeWithText(res.getString(R.string.settings_weather_app)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_left_edge_none)).assertIsDisplayed()
    }

    @Test fun theChosenAppsNameShowsUnderTheLabel() {
        show(appLabel = "Weather & Radar")
        compose.onNodeWithText("Weather & Radar").assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_left_edge_none)).assertDoesNotExist()
    }

    @Test fun aTapOnTheRowOpensThePicker() {
        show(appLabel = null)
        compose.onNodeWithText(res.getString(R.string.settings_weather_app)).performClick()
        assertThat(clicks).isEqualTo(1)
    }
}
