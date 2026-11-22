package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings opens before its view model has the stored values. Drawn from the defaults meanwhile, the
 * Appearance row named the default base colour for a few frames, on a page already painted in the
 * chosen one (seen in a cold-start recording). Nothing is named until the values are read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SettingsFirstFramesTest {
    @get:Rule val compose = createComposeRule()

    @Before fun nothingRemembered() = SettingsScrollMemory.clear()
    private val res = RuntimeEnvironment.getApplication().resources
    private val recorded = RecordedActions()

    private fun summary(colourName: Int) =
        hasText(res.getString(R.string.settings_cat_appearance_summary_value, res.getString(colourName)))

    @Test fun nothingIsNamedBeforeTheStoredValuesAreRead() {
        compose.setContent { SettingsWhenRead(s = null, lists = SettingsLists(), actions = recorded.actions) }
        compose.waitForIdle()

        compose.onNodeWithText(res.getString(R.string.settings_title)).assertDoesNotExist()
        compose.onNodeWithTag(categoryTag(SettingsCategory.APPEARANCE.key)).assertDoesNotExist()
        compose.onNode(summary(R.string.base_colour_orange), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun thePageComesWithTheStoredValuesOnceTheyAreRead() {
        var stored by mutableStateOf<LauncherSettings?>(null)
        compose.setContent { SettingsWhenRead(s = stored, lists = SettingsLists(), actions = recorded.actions) }
        compose.waitForIdle()

        stored = LauncherSettings(baseColour = LauncherSettings.BASE_COLOUR_TEAL)
        compose.waitForIdle()

        compose.onNodeWithText(res.getString(R.string.settings_title)).assertExists()
        compose.onNode(summary(R.string.base_colour_teal), useUnmergedTree = true).assertExists()
        compose.onNode(summary(R.string.base_colour_orange), useUnmergedTree = true).assertDoesNotExist()
    }
}
