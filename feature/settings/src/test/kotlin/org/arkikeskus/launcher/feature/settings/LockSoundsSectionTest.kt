package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings → Sounds: a switch for the lock and unlock sounds; while on, a sound for each — picked from
 * a file, from the phone's own sounds, or none — with a play button, and a way to the phone's sound
 * settings to turn its own screen locking sound off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class LockSoundsSectionTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val switched = mutableListOf<Boolean>()
    private val chosen = mutableListOf<Pair<SoundSlot, SoundSource>>()
    private val previewed = mutableListOf<String>()
    private var soundSettingsOpened = 0

    private fun show(enabled: Boolean, lockUri: String = "", unlockUri: String = "") {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LockSoundsSection(
                    enabled = enabled,
                    lockUri = lockUri,
                    unlockUri = unlockUri,
                    soundName = { uri -> if (uri.endsWith("7")) "Pixie Dust" else "doorbell.mp3" },
                    onSetEnabled = { switched += it },
                    onChoose = { slot, source -> chosen += slot to source },
                    onPreview = { previewed += it },
                    onOpenSoundSettings = { soundSettingsOpened++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = res.getString(id)

    @Test fun offShowsOnlyTheSwitch() {
        show(enabled = false)

        compose.onNodeWithText(text(R.string.settings_lock_sounds)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_lock_sound)).assertDoesNotExist()
        compose.onNode(isToggleable()).performClick()
        assertThat(switched).containsExactly(true)
    }

    @Test fun onShowsEachSoundByName() {
        show(enabled = true, lockUri = "content://media/internal/audio/media/7", unlockUri = "")

        compose.onNodeWithText(text(R.string.settings_lock_sound)).assertIsDisplayed()
        compose.onNodeWithText("Pixie Dust").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_unlock_sound)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_sound_none)).assertIsDisplayed()
    }

    /** Only a picked sound can be played; playing it previews it. */
    @Test fun aPickedSoundCanBePlayed() {
        show(enabled = true, lockUri = "content://media/internal/audio/media/7", unlockUri = "")
        val play = compose.onAllNodesWithContentDescription(text(R.string.settings_sound_play))

        play.assertCountEquals(1)
        play[0].performClick()

        assertThat(previewed).containsExactly("content://media/internal/audio/media/7")
    }

    @Test fun aSoundComesFromAFileThePhonesSoundsOrNone() {
        show(enabled = true, lockUri = "", unlockUri = "content://downloads/doc")

        compose.onNodeWithText(text(R.string.settings_unlock_sound)).performClick()
        compose.onNodeWithText(text(R.string.settings_sound_from_files)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_sound_from_phone)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_sound_from_files)).performClick()

        compose.onNodeWithText(text(R.string.settings_lock_sound)).performClick()
        compose.onNodeWithText(text(R.string.settings_sound_from_phone)).performClick()

        compose.onNodeWithText(text(R.string.settings_unlock_sound)).performClick()
        compose.onAllNodesWithText(text(R.string.settings_sound_none)).onLast().performClick()

        assertThat(chosen).containsExactly(
            SoundSlot.Unlock to SoundSource.File,
            SoundSlot.Lock to SoundSource.Phone,
            SoundSlot.Unlock to SoundSource.None,
        ).inOrder()
    }

    /** The phone's own lock sound can't be turned off by an app: the section points the way there. */
    @Test fun thePhonesSoundSettingsAreOneTapAway() {
        show(enabled = true)

        compose.onNodeWithText(text(R.string.settings_phone_sound_settings)).performClick()

        assertThat(soundSettingsOpened).isEqualTo(1)
    }
}
