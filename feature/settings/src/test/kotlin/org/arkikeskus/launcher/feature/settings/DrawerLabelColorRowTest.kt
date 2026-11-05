package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app drawer's label colour row (the owner's 1.0.1 ask: the same choice home's labels have): an "Automatic"
 * chip for the black or white the drawer picks by its own look, and the nine label swatches; the chosen one is
 * marked, a tap reports the colour or null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerLabelColorRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val picked = mutableListOf<Int?>()
    private val blue = 0xFF42A5F5.toInt()

    private fun show(selected: Int?) {
        compose.setContent { DrawerLabelColorRow(selected = selected, onPick = { picked += it }) }
        compose.waitForIdle()
    }

    private fun automatic() = compose.onNodeWithText(res.getString(R.string.settings_color_automatic))

    @Test fun automaticIsTheDefaultAndASwatchPicksAColour() {
        show(selected = null)

        compose.onNodeWithText(res.getString(R.string.settings_label_color)).assertIsDisplayed()
        automatic().assertIsSelected()
        // The strip scrolls sideways; the later swatches start off a narrow screen.
        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsNotSelected().performScrollTo().performClick()

        assertThat(picked).containsExactly(blue)
    }

    @Test fun theAutomaticChipClearsAChosenColour() {
        show(selected = blue)

        compose.onNodeWithTag(colorSwatchTag(blue)).assertIsSelected()
        automatic().assertIsNotSelected().performClick()

        assertThat(picked).containsExactly(null)
    }
}
