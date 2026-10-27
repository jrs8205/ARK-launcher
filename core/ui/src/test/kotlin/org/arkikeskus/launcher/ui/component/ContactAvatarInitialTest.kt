package org.arkikeskus.launcher.ui.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A contact with no photo shows its first character: the whole character, also an emoji. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ContactAvatarInitialTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anEmojiInitialIsShownWhole() {
        compose.setContent { ContactAvatar(name = "😀 Mom", photoUri = null) }

        compose.onNodeWithText("😀").assertExists()
    }

    @Test fun aLetterIsUpperCased() {
        compose.setContent { ContactAvatar(name = "mia", photoUri = null) }

        compose.onNodeWithText("M").assertExists()
    }
}
