package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Settings ▸ Feedback lists every channel the README does: GitHub Discussions, the Telegram channel (the
 * release announcements, in place of the group since 1.0.3) and,
 * since 1.0.2, the r/ArkLauncher community (owner decision 2026-11-05). Each row opens its own page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FeedbackRowsTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val opened = mutableListOf<String>()

    private fun show() {
        // In a Column as in the Feedback section: the rows are slot content, not a layout of their own.
        compose.setContent { Column { FeedbackRows(onOpen = { opened += it }) } }
        compose.waitForIdle()
    }

    @Test fun allThreeChannelsAreListedWithTheirDescriptions() {
        show()

        compose.onNodeWithText(res.getString(R.string.settings_feedback_discussions)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_feedback_telegram)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_feedback_reddit)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_feedback_reddit_desc)).assertIsDisplayed()
    }

    @Test fun eachRowOpensItsOwnPage() {
        show()

        compose.onNodeWithText(res.getString(R.string.settings_feedback_discussions)).performClick()
        compose.onNodeWithText(res.getString(R.string.settings_feedback_telegram)).performClick()
        compose.onNodeWithText(res.getString(R.string.settings_feedback_reddit)).performClick()

        assertThat(opened).containsExactly(DISCUSSIONS_URL, TELEGRAM_URL, REDDIT_URL).inOrder()
        assertThat(DISCUSSIONS_URL).isEqualTo("https://github.com/jrs8205/ARK-launcher/discussions")
        assertThat(TELEGRAM_URL).isEqualTo("https://t.me/ARKLauncherUpdates")
        assertThat(REDDIT_URL).isEqualTo("https://www.reddit.com/r/ArkLauncher/")
    }
}
