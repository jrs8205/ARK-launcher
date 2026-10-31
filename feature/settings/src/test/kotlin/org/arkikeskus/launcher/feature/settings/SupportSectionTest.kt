package org.arkikeskus.launcher.feature.settings

import android.app.Application
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
 * The support section at the very bottom of Settings (owner decision 2026-10-31): a title, one sentence,
 * and two plain buttons that open the sponsor pages in the browser. Nothing else: no reminders, no
 * in-app payment, no third-party logos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SupportSectionTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val opened = mutableListOf<String>()

    private fun show() {
        compose.setContent { SupportSection(onOpen = { opened += it }) }
        compose.waitForIdle()
    }

    @Test fun theSectionShowsItsTitleTextAndBothButtons() {
        show()

        compose.onNodeWithText(res.getString(R.string.settings_support_title)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_support_text)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_support_github)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_support_coffee)).assertIsDisplayed()
    }

    @Test fun eachButtonOpensItsOwnPage() {
        show()

        compose.onNodeWithText(res.getString(R.string.settings_support_github)).performClick()
        compose.onNodeWithText(res.getString(R.string.settings_support_coffee)).performClick()

        assertThat(opened).containsExactly(SPONSOR_URL, COFFEE_URL).inOrder()
        assertThat(SPONSOR_URL).isEqualTo("https://github.com/sponsors/jrs8205")
        assertThat(COFFEE_URL).isEqualTo("https://buymeacoffee.com/jrs8205")
    }
}
