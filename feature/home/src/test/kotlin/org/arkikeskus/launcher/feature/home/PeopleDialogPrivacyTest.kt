package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.arkikeskus.launcher.data.PinnedPerson
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PeopleDialogPrivacyTest {
    @get:Rule val compose = createComposeRule()
    private fun tile(name: String, pinned: Boolean = false) = PeopleWidgetViewModel.Tile(
        key = name, name = name, pinned = if (pinned) PinnedPerson(name, name) else null,
        live = null, contact = null,
    )

    @Test fun countOnlyReplyDoesNotRevealRecipient() {
        compose.setContent {
            MaterialTheme { ReplyDialog(tile("Private Recipient"), LauncherSettings.PRIVACY_COUNT, {}, {}) }
        }
        compose.onNodeWithText("Private Recipient", substring = true).assertDoesNotExist()
        compose.onNodeWithText("New message", substring = true).assertIsDisplayed()
    }

    @Test fun countOnlyLinkHidesSourceAndUnpinnedCandidatesButKeepsPinnedIdentity() {
        compose.setContent {
            MaterialTheme {
                LinkDialog(
                    tile("Private Source"), LauncherSettings.PRIVACY_COUNT,
                    listOf(tile("Hidden Candidate"), tile("Pinned Friend", pinned = true)), {}, {},
                )
            }
        }
        compose.onNodeWithText("Private Source", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Hidden Candidate", substring = true).assertDoesNotExist()
        compose.onNodeWithText("H").assertDoesNotExist()
        compose.onNodeWithText("Pinned Friend").assertIsDisplayed()
    }

    @Test fun senderPrivacyKeepsRecipientAvailableInReply() {
        compose.setContent {
            MaterialTheme { ReplyDialog(tile("Visible Recipient"), LauncherSettings.PRIVACY_SENDER, {}, {}) }
        }
        compose.onNodeWithText("Visible Recipient", substring = true).assertIsDisplayed()
    }
}
