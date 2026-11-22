package org.arkikeskus.launcher.feature.settings

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review round 10. A page starts anew with another home profile (its key), and what a page keeps is restored
 * after a recreation only under the same key. So a system picker opened on a page lost its answer when, under
 * the open picker, the profile changed AND Settings was recreated (a rotation): nothing was registered under
 * the request the picker answers. The new backup file stayed empty with no word of it, and the sound that was
 * picked was not set. The picture behind the drawer had been taken above the pages for this (round 9); here
 * the rest of the pickers: the backup page, and the two pickers of each lock sound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
// The screensaver page draws its styles' thumbnails.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerRequestsAcrossProfileSwitchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Settings opens where it was left: each test starts on the main page. */
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
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    private var settings by mutableStateOf(LauncherSettings(homeProfileId = 2, lockSoundsEnabled = true))

    /** The files the stand-in backup page was handed, each with the profile it had asked for. */
    private val backupFiles = mutableListOf<Pair<Uri?, Long?>>()

    /**
     * What the backup page is to Settings: a screen with a picker request of its own, and beside it what the
     * request was made for (the export keeps the profile of the tap so). Both are kept by the page itself.
     */
    @Composable private fun BackupStandIn() {
        var askedFor by rememberSaveable { mutableStateOf<Long?>(null) }
        val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            backupFiles += uri to askedFor
        }
        Box(
            Modifier.size(48.dp).testTag(BACKUP_ASK_TAG).clickable {
                askedFor = settings.homeProfileId
                createFile.launch("backup.json")
            },
        )
    }

    private fun show() {
        tester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                SettingsContent(
                    s = settings,
                    lists = SettingsLists(),
                    actions = recorded.actions,
                    blurAvailability = BlurAvailability.AVAILABLE,
                    backupScreen = { BackupStandIn() },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun open(category: SettingsCategory) {
        compose.onNodeWithTag(categoryTag(category.key)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Taps a sound's row and, in the question that opens, where the sound is to come from. */
    private fun choose(sound: Int, from: Int) {
        compose.onAllNodesWithText(res.getString(sound)).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(res.getString(from)).onLast().performClick()
        compose.waitForIdle()
    }

    /** The system's picker is in front; under it the other profile comes on and Settings is recreated. */
    private fun theProfileChangesAndSettingsIsRecreatedUnderThePicker() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        settings = settings.copy(homeProfileId = 1)
        compose.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
    }

    private fun <O> thePickerAnswers(result: O) {
        compose.runOnUiThread { registry.dispatchResult(launched.last(), result) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test fun aBackupsNewFileReachesThePageThatAskedForItThoughTheProfileChangedAndSettingsWasRecreated() {
        show()
        open(SettingsCategory.BACKUP)
        compose.onNodeWithTag(BACKUP_ASK_TAG).performClick()
        compose.waitForIdle()
        assertThat(launched).hasSize(1)

        theProfileChangesAndSettingsIsRecreatedUnderThePicker()
        val file = Uri.parse("content://documents/backup.json")
        thePickerAnswers<Uri?>(file)

        // With the profile the page had asked for: the page's own word of its request came back with it.
        assertThat(backupFiles).containsExactly(file to 2L)
    }

    @Test fun aSoundPickedFromTheFilesIsSetThoughTheProfileChangedAndSettingsWasRecreated() {
        show()
        open(SettingsCategory.SOUNDS)
        choose(R.string.settings_lock_sound, from = R.string.settings_sound_from_files)
        assertThat(launched).hasSize(1)

        theProfileChangesAndSettingsIsRecreatedUnderThePicker()
        val file = Uri.parse("content://com.example.documents/document/chime")
        thePickerAnswers<Uri?>(file)

        assertThat(recorded.calls).contains("setLockSound" to listOf(SoundSlot.Lock, file.toString()))
    }

    @Test fun aSoundPickedFromThePhonesOwnIsSetThoughTheProfileChangedAndSettingsWasRecreated() {
        show()
        open(SettingsCategory.SOUNDS)
        choose(R.string.settings_unlock_sound, from = R.string.settings_sound_from_phone)
        assertThat(launched).hasSize(1)

        theProfileChangesAndSettingsIsRecreatedUnderThePicker()
        val sound = Uri.parse("content://media/internal/audio/media/7")
        thePickerAnswers(ActivityResult(Activity.RESULT_OK, Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, sound)))

        assertThat(recorded.calls).contains("setLockSound" to listOf(SoundSlot.Unlock, sound.toString()))
    }

    /** Each sound has a request of its own for each picker: an answer is for the sound its picker was opened
     *  for, with nothing kept beside the request to say which. */
    @Test fun eachSoundsPickerAnswersForItsOwnSound() {
        show()
        open(SettingsCategory.SOUNDS)
        val chime = Uri.parse("content://com.example.documents/document/chime")
        val click = Uri.parse("content://com.example.documents/document/click")

        choose(R.string.settings_unlock_sound, from = R.string.settings_sound_from_files)
        thePickerAnswers<Uri?>(click)
        choose(R.string.settings_lock_sound, from = R.string.settings_sound_from_files)
        thePickerAnswers<Uri?>(chime)
        choose(R.string.settings_lock_sound, from = R.string.settings_sound_from_phone)
        // The phone's picker closed with nothing picked: nothing is set.
        thePickerAnswers(ActivityResult(Activity.RESULT_CANCELED, null))

        assertThat(recorded.calls.filter { it.first == "setLockSound" }).containsExactly(
            "setLockSound" to listOf(SoundSlot.Unlock, click.toString()),
            "setLockSound" to listOf(SoundSlot.Lock, chime.toString()),
        ).inOrder()
    }

    // A permission question is a request too. For these two the ANSWER is what turns the switch on, so an
    // answer that found nobody left the permission given and the switch off.

    @Test fun contactSearchTurnsOnWithTheGrantThoughTheProfileChangedAndSettingsWasRecreated() {
        show()
        open(SettingsCategory.DRAWER)
        toggle("Q1")
        assertThat(launched).hasSize(1)

        theProfileChangesAndSettingsIsRecreatedUnderThePicker()
        thePickerAnswers(true)

        assertThat(recorded.calls).contains("setSearchContacts" to listOf(true))
    }

    @Test fun theBatteryFullNoticeTurnsOnWithTheGrantThoughTheProfileChangedAndSettingsWasRecreated() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.screensaverPreferences()
        prefs.edit().clear().commit()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        open(SettingsCategory.SCREENSAVER)
        toggle("SS4")
        assertThat(launched).hasSize(1)

        theProfileChangesAndSettingsIsRecreatedUnderThePicker()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        thePickerAnswers(true)

        assertThat(prefs.getBoolean(DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION, false)).isTrue()
    }

    private fun toggle(id: String) {
        compose.onNodeWithTag(settingTag(id)).performScrollTo()
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag(settingTag(id))), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val BACKUP_ASK_TAG = "backup-stand-in-ask"
    }
}
