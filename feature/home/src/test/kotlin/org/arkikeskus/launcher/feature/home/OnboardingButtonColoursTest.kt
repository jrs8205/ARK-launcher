package org.arkikeskus.launcher.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The intro is drawn on its own aurora, but its buttons are the launcher's: they take the base
 * colour's fill with its content colour on it. On yellow that content is dark, not the intro's white.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class OnboardingButtonColoursTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val yellow = basePalette(PaletteSeed.Hue(46f, 1f), dark = true)

    private fun colourOf(label: String): Color {
        val node = compose.onAllNodesWithText(label, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().layoutInput.style.color
    }

    @Test fun theIntrosButtonIsWrittenInTheFillsContentColour() {
        compose.setContent {
            LauncherTheme(palette = yellow) {
                OnboardingFlow(onFinish = {}, onContactsGranted = {}, onPhoneGranted = {})
            }
        }
        assertThat(yellow.onFill).isNotEqualTo(Color.White)
        assertThat(colourOf("Next")).isEqualTo(yellow.onFill)
    }
}
