package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Settings page's body takes the settings, its lists and its actions as parameters, so the whole
 * page can be shown in a test without the view model, and every row reports through the actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SettingsContentTest {
    @get:Rule val compose = createComposeRule()

    @Before fun nothingRemembered() = SettingsScrollMemory.clear()
    private val res = RuntimeEnvironment.getApplication().resources
    private val recorded = RecordedActions()

    private fun show(settings: LauncherSettings = LauncherSettings()) {
        compose.setContent { SettingsContent(s = settings, lists = SettingsLists(), actions = recorded.actions) }
        compose.waitForIdle()
    }

    @Test fun thePageRendersWithoutAViewModel() {
        show()
        compose.onNodeWithText(res.getString(R.string.settings_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.settings_set_default)).assertExists()
        compose.onNodeWithTag(categoryTag(SettingsCategory.GESTURES.key)).assertExists()
    }

    @Test fun aRowReportsThroughTheActions() {
        show(LauncherSettings(swipeUpForDrawer = true))
        compose.onNodeWithTag(categoryTag(SettingsCategory.GESTURES.key)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(isToggleable() and hasAnySibling(hasText(res.getString(R.string.settings_swipe_up_drawer))))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertThat(recorded.calls).containsExactly("setSwipeUp" to listOf(false))
    }
}
