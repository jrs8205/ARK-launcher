package org.arkikeskus.launcher.feature.backup

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An export is for the profile its button names when it is tapped. The system's file picker is open between the
 * tap and the file: the profile that is on can be another one by the time it answers, and the screen can have
 * been recreated under it. The export still asks for the profile of the tap (and refuses to write another).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ExportButtonTest {
    @get:Rule val compose = createComposeRule()

    private val tester = StateRestorationTester(compose)
    private val launched = mutableListOf<Int>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += requestCode
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    private val list = listOf(HomeProfile(1, ""), HomeProfile(2, "Work"))
    private var profiles by mutableStateOf<RestoreProfiles?>(profilesARestoreDeletes(list, activeId = 2))
    private val exports = mutableListOf<Pair<Uri, Long>>()
    private val file: Uri = Uri.parse("content://documents/backup.json")

    private fun show() {
        tester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                ExportButton(profiles, guarded = { it() }, onExport = { uri, profile -> exports += uri to profile })
            }
        }
        compose.waitForIdle()
    }

    private fun thePickerAnswers() {
        compose.runOnUiThread { registry.dispatchResult(launched.single(), android.app.Activity.RESULT_OK, android.content.Intent().setData(file)) }
        compose.waitForIdle()
    }

    @Test fun theExportIsForTheProfileTheButtonNamedThoughAnotherIsOnWhenThePickerAnswers() {
        show()
        compose.onNodeWithTag(EXPORT_BUTTON_TAG).performClick()
        compose.waitForIdle()
        assertThat(launched).hasSize(1)

        // The profile is switched while the picker is open: the button now names the first profile.
        profiles = profilesARestoreDeletes(list, activeId = 1)
        compose.waitForIdle()
        thePickerAnswers()

        assertThat(exports).containsExactly(file to 2L)
    }

    @Test fun theProfileOfTheTapSurvivesARecreationUnderThePicker() {
        show()
        compose.onNodeWithTag(EXPORT_BUTTON_TAG).performClick()
        compose.waitForIdle()

        profiles = profilesARestoreDeletes(list, activeId = 1)
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        thePickerAnswers()

        assertThat(exports).containsExactly(file to 2L)
    }

    /** Before the profiles are read there is no profile to hold the export to. */
    @Test fun theButtonWaitsUntilTheProfilesAreKnown() {
        profiles = null
        show()
        compose.onNodeWithTag(EXPORT_BUTTON_TAG).assertIsNotEnabled()

        profiles = profilesARestoreDeletes(list, activeId = 1)
        compose.waitForIdle()
        compose.onNodeWithTag(EXPORT_BUTTON_TAG).assertIsEnabled()
    }
}
