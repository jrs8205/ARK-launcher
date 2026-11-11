package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A row found by the search is opened AT the row: its page scrolls it into view, marks it for a moment and hands it
 * the focus (which a screen reader follows). A row that is not on screen, because the setting it depends on is off,
 * leads to that setting instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SettingsSearchJumpTest {
    @get:Rule val compose = createComposeRule()

    @Before fun nothingRemembered() = SettingsScrollMemory.clear()

    private val res = RuntimeEnvironment.getApplication().resources

    private fun show(settings: LauncherSettings = LauncherSettings()) {
        compose.setContent {
            SettingsContent(
                s = settings,
                lists = SettingsLists(),
                actions = RecordedActions().actions,
                blurAvailability = BlurAvailability.AVAILABLE,
                backupScreen = {},
            )
        }
        compose.waitForIdle()
    }

    /** Types [query], taps the row found for [id], and stops the clock while that row is still marked. */
    private fun openFromSearch(query: String, id: String) {
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(query)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(searchHitTag(id)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun letTheMarkFade() {
        compose.mainClock.advanceTimeBy(4_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    /** The row has not been handed the focus (a row that was never a target cannot hold it at all). */
    private fun assertNotFocused(id: String) {
        val focused = compose.onNodeWithTag(settingTag(id)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)
        assertWithMessage("row $id holds the focus").that(focused ?: false).isFalse()
    }

    private fun tags(prefix: String): List<String> =
        compose.onAllNodes(
            SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    @Test fun aRowFoundIsBroughtIntoViewAndTakesTheFocus() {
        show()
        // The last row of the longest page: far below the first screen.
        openFromSearch(res.getString(R.string.settings_mix_drawer_folders), "A21")
        compose.onNodeWithTag(settingTag("A21")).assertIsDisplayed().assertIsFocused()
        letTheMarkFade()
        compose.onNodeWithTag(settingTag("A21")).assertIsDisplayed()
        assertNotFocused("A21")
    }

    @Test fun aRowThatIsNotShownLeadsToTheSettingItDependsOn() {
        show(LauncherSettings(homeGlassPanel = false))
        openFromSearch(res.getString(R.string.settings_glass_strength), "H8")
        compose.onNodeWithTag(settingTag("H8")).assertDoesNotExist()
        compose.onNodeWithTag(settingTag("H7")).assertIsDisplayed().assertIsFocused()
        letTheMarkFade()
    }

    @Test fun theSameRowIsItselfTheTargetOnceItShows() {
        show(LauncherSettings(homeGlassPanel = true))
        openFromSearch(res.getString(R.string.settings_glass_strength), "H8")
        compose.onNodeWithTag(settingTag("H8")).assertIsDisplayed().assertIsFocused()
        assertNotFocused("H7")
        letTheMarkFade()
    }

    @Test fun aRowOfTheMainPageIsShownOnTheMainPage() {
        show()
        openFromSearch(res.getString(R.string.settings_support_paypal), "E1")
        compose.onNodeWithTag(settingTag("E1")).assertIsDisplayed().assertIsFocused()
        letTheMarkFade()
        // The search was cleared to show it, so the categories are back.
        assertThat(tags("category-")).hasSize(SettingsCategory.entries.size)
    }

    @Test fun openingAPageFromItsCategoryMarksNothing() {
        show()
        compose.onNodeWithTag(categoryTag(SettingsCategory.DOCK.key)).performScrollTo().performClick()
        compose.waitForIdle()
        for (id in listOf("D1", "D2", "D3", "D4", "D5")) assertNotFocused(id)
    }

    @Test fun aRowFoundOnceIsNotMarkedAgainWhenItsPageIsOpenedLater() {
        show()
        openFromSearch(res.getString(R.string.settings_dock_show), "D1")
        compose.onNodeWithTag(settingTag("D1")).assertIsFocused()
        letTheMarkFade()
        // Back to what was found, clear it, and open the same page from its category.
        compose.onNodeWithContentDescription(res.getString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(res.getString(R.string.settings_search_clear)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(categoryTag(SettingsCategory.DOCK.key)).performScrollTo().performClick()
        compose.waitForIdle()
        assertNotFocused("D1")
    }
}
