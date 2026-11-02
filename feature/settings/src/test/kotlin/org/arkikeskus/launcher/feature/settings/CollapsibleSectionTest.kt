package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
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
 * A Telegram request: every settings section folds under its title. The title is a button that
 * reports its state to TalkBack; a tap asks the host to flip it, and the rows show only while the
 * section is open (every section starts open, as before).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class CollapsibleSectionTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private var collapsed by mutableStateOf(false)
    private val toggles = mutableListOf<Boolean>()

    private fun show() {
        compose.setContent {
            CollapsibleSection(title = "Dock", collapsed = collapsed, onToggle = { toggles += it; collapsed = it }) {
                Text("Show the dock")
            }
        }
        compose.waitForIdle()
    }

    private fun stateIs(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test fun anOpenSectionShowsItsRowsAndSaysSo() {
        show()

        compose.onNodeWithText("Show the dock").assertIsDisplayed()
        compose.onNodeWithText("Dock").assertHasClickAction().assert(stateIs(res.getString(R.string.settings_section_expanded)))
    }

    @Test fun aTapOnTheTitleFoldsTheRowsAwayAndAnotherBringsThemBack() {
        show()

        compose.onNodeWithText("Dock").performClick()
        compose.waitForIdle()
        assertThat(toggles).containsExactly(true)
        compose.onNodeWithText("Show the dock").assertDoesNotExist()
        compose.onNodeWithText("Dock").assert(stateIs(res.getString(R.string.settings_section_collapsed)))

        compose.onNodeWithText("Dock").performClick()
        compose.waitForIdle()
        assertThat(toggles).containsExactly(true, false).inOrder()
        compose.onNodeWithText("Show the dock").assertIsDisplayed()
    }
}
