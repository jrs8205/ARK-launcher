package org.arkikeskus.launcher.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issue #23: the icon editor for a pinned shortcut, the Edit dialog's icon half without a name field:
 * the shortcut's icon (its own or the picked one) with a tap to change it, Save, Reset to the pinned
 * icon and Cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class EditIconDialogTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val picked = CustomIcon.FromPack("com.example.pack", "octocat")
    private val saved = mutableListOf<CustomIcon?>()
    private var resets = 0
    private var dismissed = 0

    private fun show(icon: CustomIcon?) {
        compose.setContent {
            EditIconDialog(
                label = "GitHub",
                icon = icon,
                original = { size -> Box(Modifier.size(size).testTag("original")) },
                onSave = { saved += it },
                onReset = { resets++ },
                onDismiss = { dismissed++ },
            )
        }
        compose.waitForIdle()
    }

    @Test fun theDialogShowsTheTitleTheLabelAndTheIconToTap() {
        show(icon = null)

        compose.onNodeWithText(res.getString(R.string.edit_title)).assertIsDisplayed()
        compose.onNodeWithText("GitHub").assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.edit_icon_hint)).assertIsDisplayed()
        compose.onNodeWithContentDescription(res.getString(R.string.icon_change)).assertIsDisplayed()
        // Inside the tappable icon, whose semantics merge its children.
        compose.onNodeWithTag("original", useUnmergedTree = true).assertExists()
    }

    @Test fun saveHandsOnTheIconAndCloses() {
        show(icon = picked)

        compose.onNodeWithText(res.getString(R.string.rename_save)).performClick()

        assertThat(saved).containsExactly(picked)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun resetAsksForThePinnedIconBackAndCloses() {
        show(icon = picked)

        compose.onNodeWithText(res.getString(R.string.rename_reset)).performClick()

        assertThat(resets).isEqualTo(1)
        assertThat(saved).isEmpty()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test fun cancelChangesNothing() {
        show(icon = picked)

        compose.onNodeWithText(res.getString(R.string.rename_cancel)).performClick()

        assertThat(saved).isEmpty()
        assertThat(resets).isEqualTo(0)
        assertThat(dismissed).isEqualTo(1)
    }
}
