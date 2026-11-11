package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The search field on the main page: what is typed replaces the category list with the rows it finds, each named by
 * its page; clearing it, or Back, brings the categories back; a row found opens its page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SettingsSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun nothingRemembered() = SettingsScrollMemory.clear()

    private val res = RuntimeEnvironment.getApplication().resources

    private fun show() {
        compose.setContent {
            SettingsContent(
                s = LauncherSettings(),
                lists = SettingsLists(),
                actions = RecordedActions().actions,
                blurAvailability = BlurAvailability.AVAILABLE,
                backupScreen = {},
            )
        }
        compose.waitForIdle()
    }

    private fun type(text: String) {
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput(text)
        compose.waitForIdle()
    }

    private fun tags(prefix: String): List<String> =
        compose.onAllNodes(
            SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    private fun systemBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }

    @Test fun theFieldSitsOnTheMainPageWithItsHint() {
        show()
        compose.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.settings_search_hint)).assertExists()
        assertThat(tags("category-")).hasSize(SettingsCategory.entries.size)
        assertThat(tags("search-hit-")).isEmpty()
    }

    @Test fun whatIsTypedReplacesTheCategoriesWithTheRowsFound() {
        show()
        type("dock")
        assertThat(tags("category-")).isEmpty()
        assertThat(tags("setting-")).isEmpty()
        assertThat(tags("search-hit-")).containsAtLeast(searchHitTag("D1"), searchHitTag("D2"), searchHitTag("D4"))
        // Each row found says which page it is on.
        assertThat(textOf(searchHitTag("D1"))).contains(res.getString(R.string.settings_dock_show))
        assertThat(textOf(searchHitTag("D1"))).contains(res.getString(R.string.settings_dock))
    }

    @Test fun theSameTitleOnThreePagesIsThreeRowsNamedByTheirPage() {
        show()
        type("show labels")
        assertThat(tags("search-hit-")).containsExactly(searchHitTag("H3"), searchHitTag("D3"), searchHitTag("A2"))
        assertThat(textOf(searchHitTag("H3"))).contains(res.getString(R.string.settings_home))
        assertThat(textOf(searchHitTag("D3"))).contains(res.getString(R.string.settings_dock))
        assertThat(textOf(searchHitTag("A2"))).contains(res.getString(R.string.settings_drawer))
    }

    @Test fun theNumberFoundIsSaidPolitely() {
        show()
        type("show labels")
        val count = res.getQuantityString(R.plurals.settings_search_count, 3, 3)
        val node = compose.onNodeWithText(count).fetchSemanticsNode()
        assertThat(node.config[SemanticsProperties.LiveRegion]).isEqualTo(LiveRegionMode.Polite)
    }

    @Test fun nothingFoundSaysSoPolitely() {
        show()
        type("zzzzqq")
        assertThat(tags("search-hit-")).isEmpty()
        val node = compose.onNodeWithText(res.getString(R.string.settings_search_none)).fetchSemanticsNode()
        assertThat(node.config[SemanticsProperties.LiveRegion]).isEqualTo(LiveRegionMode.Polite)
        assertThat(tags("category-")).isEmpty()
    }

    @Test fun onlySpacesIsNoSearch() {
        show()
        type("   ")
        assertThat(tags("category-")).hasSize(SettingsCategory.entries.size)
    }

    @Test fun theClearButtonBringsTheCategoriesBack() {
        show()
        compose.onNodeWithContentDescription(res.getString(R.string.settings_search_clear)).assertDoesNotExist()
        type("dock")
        compose.onNodeWithContentDescription(res.getString(R.string.settings_search_clear)).performClick()
        compose.waitForIdle()
        assertThat(tags("category-")).hasSize(SettingsCategory.entries.size)
        compose.onNodeWithText(res.getString(R.string.settings_search_hint)).assertExists()
    }

    @Test fun backClearsTheSearchBeforeItLeavesSettings() {
        show()
        type("dock")
        systemBack()
        assertThat(tags("category-")).hasSize(SettingsCategory.entries.size)
        assertThat(compose.activity.isFinishing).isFalse()
        systemBack()
        assertThat(compose.activity.isFinishing).isTrue()
    }

    @Test fun aRowFoundOpensItsPageAndBackReturnsToWhatWasFound() {
        show()
        type("dock")
        compose.onNodeWithTag(searchHitTag("D4")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasText(res.getString(R.string.settings_dock)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertExists()
        compose.onNodeWithTag(settingTag("D4")).assertExists()
        systemBack()
        assertThat(tags("search-hit-")).contains(searchHitTag("D4"))
        assertThat(tags("category-")).isEmpty()
    }

    @Test fun theBackupRowFoundOpensTheBackupScreen() {
        show()
        type(res.getString(R.string.settings_backup))
        assertThat(tags("search-hit-")).contains(searchHitTag("B1"))
    }

    @Test fun rowsThisAndroidCannotHaveAreNotOffered() {
        val android12 = searchableSettings(res, sdkInt = 32).map { it.id }
        val android13 = searchableSettings(res, sdkInt = 33).map { it.id }
        assertThat(android12).containsNoneOf("I5", "I6")
        assertThat(android13).containsAtLeast("I5", "I6")
        assertThat(android13).hasSize(SettingsIndex.entries.size)
    }

    @Test fun whatIsSearchedIsTheTextOfTheCurrentLanguage() {
        val dock = searchableSettings(res, sdkInt = 35).first { it.id == "D1" }
        assertThat(dock.title).isEqualTo(res.getString(R.string.settings_dock_show))
        val support = searchableSettings(res, sdkInt = 35).first { it.id == "E1" }
        assertThat(support.keywords).contains(res.getString(R.string.settings_support_paypal))
        assertThat(support.description).isEqualTo(res.getString(R.string.settings_support_text))
    }
}
