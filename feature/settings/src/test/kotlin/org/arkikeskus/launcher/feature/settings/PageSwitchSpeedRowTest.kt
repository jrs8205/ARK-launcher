package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Settings > Home > "Page switch speed": the row names the current choice and a tap opens the picker (issue #18). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PageSwitchSpeedRowTest {
    @get:Rule val compose = createComposeRule()
    private val res = RuntimeEnvironment.getApplication().resources
    private var clicks = 0

    private fun show(selected: String) {
        compose.setContent { PageSwitchSpeedRow(selected = selected) { clicks++ } }
        compose.waitForIdle()
    }

    @Test fun theRowNamesTheCurrentChoice() {
        show(LauncherSettings.SPEED_FAST)
        compose.onNodeWithText(res.getString(R.string.settings_page_switch_speed)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_page_speed_fast)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_page_speed_normal)).assertDoesNotExist()
    }

    @Test fun anUnknownValueReadsAsNormal() {
        show("garbage")
        compose.onNodeWithText(res.getString(R.string.settings_page_speed_normal)).assertIsDisplayed()
    }

    @Test fun aTapOnTheRowOpensThePicker() {
        show(LauncherSettings.SPEED_NORMAL)
        compose.onNodeWithText(res.getString(R.string.settings_page_switch_speed)).performClick()
        assertThat(clicks).isEqualTo(1)
    }
}
