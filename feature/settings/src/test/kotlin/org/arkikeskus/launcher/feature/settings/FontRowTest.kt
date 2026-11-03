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
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The font row (a Telegram request after 0.9.9): one chip per family Android ships, the chosen one
 * marked, a tap reports the family's settings key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FontRowTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val picked = mutableListOf<String>()

    private fun show(selected: String) {
        compose.setContent { FontRow(selected = selected, onPick = { picked += it }) }
        compose.waitForIdle()
    }

    @Test fun everyFamilyIsOfferedAndTheDefaultIsMarked() {
        show(LauncherSettings.FONT_DEFAULT)

        compose.onNodeWithText(res.getString(R.string.settings_font)).assertIsDisplayed()
        LauncherSettings.FONTS.forEach { compose.onNodeWithTag(fontChipTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag(fontChipTag(LauncherSettings.FONT_DEFAULT)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(fontChipTag(LauncherSettings.FONT_SERIF)).performScrollTo().assertIsNotSelected()
        assertThat(LauncherSettings.FONTS).hasSize(6)
    }

    @Test fun aChipPicksItsFamily() {
        show(LauncherSettings.FONT_DEFAULT)

        compose.onNodeWithTag(fontChipTag(LauncherSettings.FONT_SERIF)).performScrollTo().performClick()
        compose.onNodeWithTag(fontChipTag(LauncherSettings.FONT_CURSIVE)).performScrollTo().performClick()

        assertThat(picked).containsExactly(LauncherSettings.FONT_SERIF, LauncherSettings.FONT_CURSIVE).inOrder()
    }

    @Test fun theChosenFamilyIsMarked() {
        show(LauncherSettings.FONT_MONOSPACE)

        compose.onNodeWithTag(fontChipTag(LauncherSettings.FONT_MONOSPACE)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(fontChipTag(LauncherSettings.FONT_DEFAULT)).performScrollTo().assertIsNotSelected()
    }
}
