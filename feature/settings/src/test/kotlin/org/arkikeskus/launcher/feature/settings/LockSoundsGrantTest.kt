package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A file picked as a sound keeps a read grant across restarts. When the same file is both the lock
 * and the unlock sound, clearing one must not take the grant the other still needs; once neither
 * uses the file, the grant is given back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class LockSoundsGrantTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val res = context.resources
    private val file = Uri.parse("content://com.example.documents/document/chime")

    private fun show(lockUri: String, unlockUri: String) {
        context.contentResolver.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        compose.setContent {
            // In a column, as on the settings screen.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LockSoundsSettings(enabled = true, lockUri = lockUri, unlockUri = unlockUri, onSetEnabled = {}, onSetSound = { _, _ -> })
            }
        }
        compose.waitForIdle()
    }

    private fun clearLockSound() {
        compose.onAllNodesWithText(res.getString(R.string.settings_lock_sound)).onFirst().performClick()
        compose.onAllNodesWithText(res.getString(R.string.settings_sound_none)).onLast().performClick()
        compose.waitForIdle()
    }

    private fun granted() = context.contentResolver.persistedUriPermissions.any { it.uri == file }

    @Test fun clearingOneSoundKeepsTheFileTheOtherStillUses() {
        show(lockUri = file.toString(), unlockUri = file.toString())

        clearLockSound()

        assertThat(granted()).isTrue()
    }

    @Test fun aFileNoSoundUsesAnyMoreGivesItsGrantBack() {
        show(lockUri = file.toString(), unlockUri = "")

        clearLockSound()

        assertThat(granted()).isFalse()
    }
}
