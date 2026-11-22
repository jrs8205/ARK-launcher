package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * The launcher's own "Hide Private space when locked" (Android 15, where Android's setting of that name cannot
 * be read). A hidden, locked space is opened through the drawer's search, so the switch needs the search field:
 * with the field off it is disabled and says why, and the search field's own row says what depends on it while
 * hiding is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HidePrivateSpaceRowTest {
    @get:Rule val compose = createComposeRule()

    @Before fun nothingRemembered() = SettingsScrollMemory.clear()
    private val res = RuntimeEnvironment.getApplication().resources
    private val recorded = RecordedActions()

    private fun drawerPage(settings: LauncherSettings) {
        compose.setContent {
            SettingsContent(
                s = settings,
                lists = SettingsLists(privateSpaceSeen = true),
                actions = recorded.actions,
                blurAvailability = BlurAvailability.AVAILABLE,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(categoryTag(SettingsCategory.DRAWER.key)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun row(id: String): SemanticsMatcher = hasTestTag(settingTag(id))
    private fun switchOf(id: String) = compose.onNode(isToggleable() and hasAnyAncestor(row(id)), useUnmergedTree = true)
    private fun rowSays(id: String, text: Int) =
        compose.onAllNodes(row(id) and hasAnyDescendant(hasText(res.getString(text))), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun calls(name: String) = recorded.calls.filter { it.first == name }.map { it.second }

    @Test fun theSwitchSitsUnderTheSearchFieldsRowAndTurnsHidingOn() {
        drawerPage(LauncherSettings(showDrawerSearch = true, hidePrivateSpaceWhenLocked = false))

        compose.onNodeWithTag(settingTag("A22")).performScrollTo()
        assertThat(rowSays("A22", R.string.settings_hide_private_space)).isTrue()
        assertThat(rowSays("A22", R.string.settings_hide_private_space_desc)).isTrue()
        switchOf("A22").assertIsEnabled().assertIsOff().performClick()

        assertThat(calls("setHidePrivateSpaceWhenLocked")).containsExactly(listOf(true))
    }

    @Test fun withTheSearchFieldOffTheSwitchIsDisabledAndSaysWhy() {
        drawerPage(LauncherSettings(showDrawerSearch = false, hidePrivateSpaceWhenLocked = false))

        compose.onNodeWithTag(settingTag("A22")).performScrollTo()
        assertThat(rowSays("A22", R.string.settings_hide_private_space_needs_search)).isTrue()
        assertThat(rowSays("A22", R.string.settings_hide_private_space_desc)).isFalse()
        switchOf("A22").assertIsNotEnabled().performClick()

        assertThat(calls("setHidePrivateSpaceWhenLocked")).isEmpty()
    }

    @Test fun whileHidingIsOnTheSearchFieldsRowSaysWhatDependsOnIt() {
        drawerPage(LauncherSettings(showDrawerSearch = true, hidePrivateSpaceWhenLocked = true))

        compose.onNodeWithTag(settingTag("A6")).performScrollTo()
        assertThat(rowSays("A6", R.string.settings_drawer_search_private_note)).isTrue()
        switchOf("A22").assertIsOn()
        // The field can still be turned off: the space then stays hidden until the field is back.
        switchOf("A6").assertIsEnabled()
    }

    @Test fun withHidingOffTheSearchFieldsRowSaysNothingMore() {
        drawerPage(LauncherSettings(showDrawerSearch = true, hidePrivateSpaceWhenLocked = false))

        compose.onNodeWithTag(settingTag("A6")).performScrollTo()
        assertThat(rowSays("A6", R.string.settings_drawer_search_private_note)).isFalse()
    }
}
