package org.arkikeskus.launcher.ui.component

import android.app.Application
import android.os.Process
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A work app beside its personal twin: on screen Android's badge on the picture tells them apart, and to a
 * screen reader the profile's name after the app's does, wherever the icon says the name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppIconProfileMarkTest {
    @get:Rule val compose = createComposeRule()

    private val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val workMail = mail.copy(userSerial = WORK)

    private fun withAWorkProfile(content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalProfileMarks provides mapOf(WORK to "Work"), content = content) }
        compose.waitForIdle()
    }

    private fun described(vararg values: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, values.toList())

    @Test fun aWorkTwinSaysItsProfileAndThePersonalOneItsBareName() {
        withAWorkProfile {
            Row {
                AppIcon(mail, Color.Black)
                AppIcon(workMail, Color.Black)
            }
        }

        // The picture and the label under it each say the name: the work app's say it with the profile.
        compose.onAllNodesWithContentDescription("Mail, Work").assertCountEquals(1)
        compose.onAllNodesWithText("Mail, Work").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(1)
        compose.onAllNodesWithText("Mail").assertCountEquals(1)
    }

    @Test fun withTheLabelOffTheWorkAppsPictureSaysTheProfile() {
        withAWorkProfile { AppIcon(workMail, Color.Black, showLabel = false) }

        compose.onAllNodesWithContentDescription("Mail, Work").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(0)
    }

    @Test fun aTileThatIsOneStopSaysTheProfileOnce() {
        // The open folder's tiles are one stop each (their own merge): the name and the profile, not twice over.
        withAWorkProfile {
            AppIcon(workMail, Color.Black, modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("tile"))
        }

        compose.onNodeWithTag("tile").assert(described("Mail, Work")).assert(hasTextExactly("Mail, Work"))
    }

    @Test fun aRowThatNamesTheAppItselfSaysTheProfileThroughItsIcon() {
        // A list row (a folder's "Add apps"): the icon without its label, the name as the row's own text.
        withAWorkProfile {
            Row(Modifier.clickable {}.testTag("row")) {
                AppIcon(workMail, Color.Black, showLabel = false)
                Text("Mail")
            }
        }

        compose.onNodeWithTag("row").assert(described("Mail, Work"))
    }

    @Test fun withoutAMarkForItsProfileAnAppSaysItsBareName() {
        compose.setContent { AppIcon(workMail, Color.Black) }
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription("Mail").assertCountEquals(1)
        compose.onAllNodesWithText("Mail").assertCountEquals(1)
    }

    @Test fun theShellsMarksNameTheWorkProfileAndNothingWithoutOne() {
        assertThat(profileMarks(workSerial = WORK, workName = "Work")).containsExactly(WORK, "Work")
        assertThat(profileMarks(workSerial = null, workName = "Work")).isEmpty()
    }

    private companion object {
        const val WORK = 12L
    }
}
