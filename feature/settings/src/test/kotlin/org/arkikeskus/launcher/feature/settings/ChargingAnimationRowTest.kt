package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The charging animation's switch on the Appearance page: on until it is switched off, and it says what it does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ChargingAnimationRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val recorded = RecordedActions()

    private fun show(settings: LauncherSettings) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) { AppearanceRows(settings, recorded.actions) }
        }
        compose.waitForIdle()
    }

    /** The page's one switch (the base colour above it is a grid of colours). */
    private fun theSwitch() = compose.onNode(isToggleable(), useUnmergedTree = true)

    @Test fun theSwitchIsOnUntilItIsSwitchedOff() {
        show(LauncherSettings())

        theSwitch().assertIsOn()
    }

    @Test fun theRowSaysWhatTheAnimationIs() {
        show(LauncherSettings())

        compose.onNodeWithText(res.getString(R.string.settings_charging_animation)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_charging_animation_desc)).performScrollTo().assertIsDisplayed()
    }

    @Test fun aTapSwitchesItOff() {
        show(LauncherSettings())

        theSwitch().performScrollTo().performClick()

        assertThat(recorded.calls).contains("setChargingAnimation" to listOf<Any?>(false))
    }

    @Test fun aTapSwitchesItBackOn() {
        show(LauncherSettings(chargingAnimation = false))

        theSwitch().performScrollTo().performClick()

        assertThat(recorded.calls).contains("setChargingAnimation" to listOf<Any?>(true))
    }
}
