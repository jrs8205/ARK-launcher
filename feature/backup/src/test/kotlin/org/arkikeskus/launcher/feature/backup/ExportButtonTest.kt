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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.google.common.truth.Truth.assertThat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.arkikeskus.launcher.data.LastBackup
import java.io.IOException
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

    private var profiles by mutableStateOf<LastBackup?>(LastBackup(2, 0, "Work"))
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
        profiles = LastBackup(1, 0, "")
        compose.waitForIdle()
        thePickerAnswers()

        assertThat(exports).containsExactly(file to 2L)
    }

    @Test fun theProfileOfTheTapSurvivesARecreationUnderThePicker() {
        show()
        compose.onNodeWithTag(EXPORT_BUTTON_TAG).performClick()
        compose.waitForIdle()

        profiles = LastBackup(1, 0, "")
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

        profiles = LastBackup(1, 0, "")
        compose.waitForIdle()
        compose.onNodeWithTag(EXPORT_BUTTON_TAG).assertIsEnabled()
    }

    @Test fun exportStaysAvailableAndARefusedRestoreKeepsItsFileForRetry() {
        val reading = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()
        val restored = mutableListOf<Uri>()
        val shown = RestoreProfiles(2, emptyList(), unlisted = setOf(9))
        var attempt = 0
        val vm = BackupViewModel(
            flowOf(LastBackup(2, 1_000L, "Work")),
            export = { _, _ -> error("no export requested") },
            restorer = BackupRestorer { uri, confirmed, _ ->
                assertThat(confirmed).isEqualTo(shown.confirmed)
                restored += uri
                BackupEvent.Restored(1, 0)
            },
            lost = ProfilesLostInRestore { flow {
                when (++attempt) {
                    1 -> throw IOException("cannot list pictures")
                    2 -> {
                        reading.await()
                        emit(shown)
                        fail.await()
                        throw IOException("lost before the tap")
                    }
                    else -> emit(shown)
                }
            } },
        )
        fun assertExport() {
            compose.onNodeWithText("Export profile “Work” to file").assertIsEnabled()
            compose.onNode(hasText("Last backup of “Work”", substring = true)).assertExists()
        }
        try {
            tester.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    BackupScreen(onBack = {}, viewModel = vm)
                }
            }
            compose.waitForIdle()
            assertThat(vm.profileReader.state.value).isEqualTo(RestoreProfileRead.Failed)
            assertExport()
            compose.onNodeWithText("Import from file").performClick()
            thePickerAnswers()
            compose.onNodeWithText("Restore").assertIsNotEnabled()
            assertExport()

            reading.complete(Unit)
            val tap = checkNotNull(compose.onNodeWithText("Restore").assertIsEnabled()
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            // Deliver the storage failure between composition and the dispatch of this frame's tap.
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnUiThread { fail.complete(Unit) }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                compose.runOnUiThread {
                    assertThat(vm.profileReader.state.value).isEqualTo(RestoreProfileRead.Failed)
                    tap()
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.onNodeWithText("Try again").performClick()
            assertThat(restored).isEmpty()
            val retryTap = checkNotNull(compose.onNodeWithText("Restore").assertIsEnabled()
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.runOnUiThread {
                retryTap()
                retryTap()
            }
            compose.waitForIdle()
            assertThat(restored).containsExactly(file)
            assertThat(launched).hasSize(1)
            compose.onNodeWithText("Restore backup?").assertDoesNotExist()
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
