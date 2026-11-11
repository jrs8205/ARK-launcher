package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
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
 * The activity is recreated on rotation and on a theme switch, and that can happen under a system picker. Settings
 * comes back on the page it was on, with the dialog that was open, and a picker's result still reaches its row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SettingsRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Before fun nothingRemembered() = SettingsScrollMemory.clear()
    private val res = RuntimeEnvironment.getApplication().resources
    private val recorded = RecordedActions()
    private val tester = StateRestorationTester(compose)

    /** A result registry that notes what was launched and hands results back when the test says so. */
    private val launched = mutableListOf<Int>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += requestCode
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    private fun show() {
        tester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                SettingsContent(
                    s = LauncherSettings(),
                    lists = SettingsLists(),
                    actions = recorded.actions,
                    blurAvailability = BlurAvailability.AVAILABLE,
                    backupScreen = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun open(category: SettingsCategory) {
        compose.onNodeWithTag(categoryTag(category.key)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun pageTitle(title: Int) =
        hasText(res.getString(title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test fun theOpenPageSurvivesRecreation() {
        show()
        open(SettingsCategory.DOCK)
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(pageTitle(R.string.settings_dock)).assertExists()
        compose.onNodeWithTag(settingTag("D1")).assertExists()
    }

    @Test fun anOpenDialogSurvivesRecreation() {
        show()
        open(SettingsCategory.HOME)
        compose.onNodeWithTag(settingTag("H11")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(res.getString(R.string.settings_page_speed_fastest)).assertExists()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(res.getString(R.string.settings_page_speed_fastest)).assertExists()
        compose.onNode(pageTitle(R.string.settings_home)).assertExists()
    }

    @Test fun anOpenSubScreenSurvivesRecreation() {
        show()
        open(SettingsCategory.DRAWER)
        compose.onNodeWithTag(settingTag("A17")).performScrollTo().performClick()
        compose.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag(settingTag("A17")).assertDoesNotExist()
        compose.onNodeWithText(res.getString(R.string.settings_hidden_apps)).assertExists()
    }

    @Test fun aPickersResultReachesItsRowAfterRecreation() {
        show()
        open(SettingsCategory.DRAWER)
        compose.onNodeWithTag(DRAWER_WALLPAPER_PICK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(launched).hasSize(1)
        // The activity is recreated under the photo picker, then the picker answers.
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        val picture = Uri.parse("content://media/picker/0/picture/1")
        compose.runOnUiThread { registry.dispatchResult(launched.single(), picture) }
        compose.waitForIdle()
        assertThat(recorded.calls).contains("importDrawerWallpaper" to listOf(picture))
    }
}
