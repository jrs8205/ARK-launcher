package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeLabelColorRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun iconsRowsShowsOnlyAutomaticWhenTheProfileRemembersAFixedSwatch() {
        val actions = RecordedActions()
        compose.setContent {
            IconsRows(LauncherSettings(automaticHomeLabels = true, appLabelColor = -1), emptyList(), actions.actions)
        }
        compose.onNodeWithText("Automatic").assertIsSelected()
        compose.onNodeWithTag(colorSwatchTag(-1)).assertIsNotSelected()
    }

    @Test fun automaticComesFirstAndOnlyItShowsTheExplanation() {
        val selected = mutableStateOf<Int?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalHomeProfileRowMarks provides true) {
                SettingRow("I3") { LabelColorRow(selected.value) { selected.value = it } }
            }
        }
        val auto = compose.onNodeWithText("Automatic")
        val white = compose.onNodeWithTag(colorSwatchTag(-1))
        val hint = compose.onNodeWithText("Automatic follows the wallpaper", substring = true)
        auto.assertIsDisplayed().assertIsSelected()
        assertThat(auto.fetchSemanticsNode().boundsInRoot.left).isLessThan(white.fetchSemanticsNode().boundsInRoot.left)
        hint.assertIsDisplayed()
        val mark = RuntimeEnvironment.getApplication().getString(R.string.settings_profile_row_mark)
        compose.onNodeWithContentDescription(mark).assertExists()
        white.performScrollTo().performClick().assertIsSelected()
        hint.assertDoesNotExist()
        auto.performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        white.assertIsNotSelected()
        hint.assertIsDisplayed()
        val entry = SettingsIndex.entries.single { it.id == "I3" }
        assertThat(entry.description).isNotNull()
    }
}
