package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android 17 shows its own dim clock in place of the chosen screensaver in a dark room, unless "Low light
 * mode" is switched off on its screensaver page. No app can change that setting; the launcher's Screensaver
 * page says so and leads there, on the Android versions that have the switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class LowLightHintTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = RuntimeEnvironment.getApplication()

    @Test fun theHintIsForAndroid17AndLater() {
        assertThat(lowLightHintShown(sdkInt = 35)).isFalse()
        assertThat(lowLightHintShown(sdkInt = 36)).isFalse()
        assertThat(lowLightHintShown(sdkInt = 37)).isTrue()
        assertThat(lowLightHintShown(sdkInt = 38)).isTrue()
    }

    @Test fun theRegistryKnowsTheRowByTheSameRule() {
        val row = SettingsIndex.entry("SS24")

        assertThat(row.category).isEqualTo(SettingsCategory.SCREENSAVER)
        assertThat(row.available(sdkInt = 36, privateSpaceSeen = false)).isFalse()
        assertThat(row.available(sdkInt = 37, privateSpaceSeen = false)).isTrue()
        assertThat(row.minSdk).isEqualTo(LOW_LIGHT_MODE_SDK)
    }

    @Test fun theRowSaysWhatAndroidDoesAndWhereToChangeIt() {
        compose.setContent { LowLightHintRow() }

        compose.onNodeWithText(app.getString(R.string.settings_screensaver_low_light)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.settings_screensaver_low_light_desc)).assertIsDisplayed()
        assertThat(app.getString(R.string.settings_screensaver_low_light_desc)).contains("Low light mode")
    }

    @Test fun aTapOpensAndroidsScreensaverPage() {
        compose.setContent { LowLightHintRow() }

        compose.onNodeWithText(app.getString(R.string.settings_screensaver_low_light)).performClick()

        assertThat(shadowOf(app).nextStartedActivity.action).isEqualTo(Settings.ACTION_DREAM_SETTINGS)
    }
}
