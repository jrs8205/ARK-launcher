package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.toExpressive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The selected tab is a fill, so its label takes the fill's content colour: on a yellow launcher a
 * white label would not read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerTabRowColoursTest {
    @get:Rule val compose = createComposeRule()

    private val yellow = basePalette(PaletteSeed.Hue(46f, 1f), dark = true).toExpressive()

    private fun colourOf(label: String): Color {
        val node = compose.onAllNodesWithText(label, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().layoutInput.style.color
    }

    @Test fun theSelectedTabsLabelIsTheFillsContentColourAndTheOtherIsText() {
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides yellow) {
                DrawerTabRow(listOf(DrawerTab.Personal, DrawerTab.Work), selected = DrawerTab.Personal, onSelect = {}, privateLocked = false)
            }
        }
        assertThat(yellow.onFill).isNotEqualTo(Color.White)
        assertThat(colourOf("Personal")).isEqualTo(yellow.onFill)
        assertThat(colourOf("Work")).isEqualTo(yellow.text)
    }
}
