package org.arkikeskus.launcher.feature.settings

import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings is a short main page of categories, each opening a page of its own. Back leaves a sub-screen for the page
 * it was opened from, a page for the main page, and the main page for wherever Settings was opened from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun nothingRemembered() = SettingsScrollMemory.clear()
    private val res = RuntimeEnvironment.getApplication().resources
    private val recorded = RecordedActions()

    private fun show(settings: LauncherSettings = LauncherSettings()) {
        compose.setContent {
            SettingsContent(
                s = settings,
                lists = SettingsLists(),
                actions = recorded.actions,
                blurAvailability = BlurAvailability.AVAILABLE,
                backupScreen = { onBack ->
                    BackHandler(onBack = onBack)
                    Box(Modifier.testTag("backup-screen"))
                },
            )
        }
        compose.waitForIdle()
    }

    private fun tagged(prefix: String) = SemanticsMatcher("has a tag starting with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun tags(prefix: String): List<String> =
        compose.onAllNodes(tagged(prefix), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }

    private fun open(category: SettingsCategory) {
        compose.onNodeWithTag(categoryTag(category.key)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertOnMainPage() {
        compose.onNodeWithText(res.getString(R.string.settings_title)).assertExists()
        assertThat(tags("category-")).hasSize(SettingsCategory.entries.size)
    }

    @Test fun theMainPageListsTheCategoriesInOrder() {
        show()
        assertThat(tags("category-")).containsExactlyElementsIn(SettingsCategory.entries.map { categoryTag(it.key) }).inOrder()
        assertThat(SettingsCategory.entries.map { it.key }).containsExactly(
            "home", "dock", "drawer", "folders", "widgets", "icons", "gestures", "bars", "notifications", "sounds",
            "permissions", "backup", "about",
        ).inOrder()
    }

    @Test fun aCategoryRowIsOneButtonNamingItsTitleAndSummary() {
        show()
        for (category in SettingsCategory.entries) {
            val node = compose.onNodeWithTag(categoryTag(category.key)).fetchSemanticsNode()
            val text = node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
            assertThat(text).contains(res.getString(category.title))
            assertThat(text).contains(res.getString(category.summary))
            assertThat(node.config[SemanticsProperties.Role]).isEqualTo(Role.Button)
        }
    }

    @Test fun theMainPageHoldsOnlyTheDefaultLauncherRowAndTheSupportBlock() {
        show()
        assertThat(tags("setting-")).containsExactly(settingTag("T2"), settingTag("E1")).inOrder()
        // The folding sections of the long page are gone.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertDoesNotExist()
    }

    @Test fun aCategoryOpensItsPageUnderItsName() {
        show()
        open(SettingsCategory.DOCK)
        compose.onNode(hasText(res.getString(R.string.settings_dock)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        assertThat(tags("category-")).isEmpty()
        assertThat(tags("setting-")).containsExactlyElementsIn(listOf("D1", "D2", "D3", "D4", "D5").map(::settingTag)).inOrder()
    }

    @Test fun systemBackReturnsFromAPageToTheMainPage() {
        show()
        open(SettingsCategory.ICONS)
        systemBack()
        assertOnMainPage()
        assertThat(compose.activity.isFinishing).isFalse()
    }

    @Test fun thePagesBackArrowReturnsToTheMainPage() {
        show()
        open(SettingsCategory.GESTURES)
        compose.onNodeWithContentDescription(res.getString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertOnMainPage()
    }

    @Test fun backOnTheMainPageLeavesSettings() {
        show()
        systemBack()
        assertThat(compose.activity.isFinishing).isTrue()
    }

    @Test fun theBackupCategoryOpensTheBackupScreenItselfAndBackReturns() {
        show()
        open(SettingsCategory.BACKUP)
        compose.onNodeWithTag("backup-screen").assertExists()
        assertThat(tags("setting-")).isEmpty()
        systemBack()
        assertOnMainPage()
    }

    @Test fun aSubScreenReturnsToThePageItWasOpenedFrom() {
        show()
        open(SettingsCategory.DRAWER)
        compose.onNodeWithTag(settingTag("A17")).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(tags("setting-")).isEmpty()
        compose.onNodeWithText(res.getString(R.string.settings_hidden_apps)).assertExists()
        systemBack()
        compose.onNodeWithTag(settingTag("A17")).assertExists()
        compose.onNode(hasText(res.getString(R.string.settings_drawer)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertExists()
        systemBack()
        assertOnMainPage()
    }

    @Test fun theDrawerFoldersSubScreenReturnsToTheDrawerPage() {
        show()
        open(SettingsCategory.DRAWER)
        compose.onNodeWithTag(settingTag("A18")).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(tags("setting-")).isEmpty()
        systemBack()
        compose.onNodeWithTag(settingTag("A18")).assertExists()
    }

    @Test fun aRowOnAPageStillReportsThroughTheActions() {
        show(LauncherSettings(dockEnabled = true))
        open(SettingsCategory.DOCK)
        compose.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(settingTag("D1"))))
            .performClick()
        compose.waitForIdle()
        assertThat(recorded.calls).containsExactly("setDockEnabled" to listOf(false))
    }

    @Test fun aPermissionGrantedInTheSystemSettingsShowsWhenThePageComesBack() {
        show()
        open(SettingsCategory.PERMISSIONS)
        val calendar = hasTestTag(settingTag("P2"))
        compose.onNode(calendar and hasAnyDescendant(hasText(res.getString(R.string.settings_permission_not_granted))), useUnmergedTree = true)
            .assertExists()
        // The system settings come on top, the permission is granted there, and the user returns.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CALENDAR)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNode(calendar and hasAnyDescendant(hasText(res.getString(R.string.settings_permission_granted))), useUnmergedTree = true)
            .assertExists()
    }

    @Test fun leavingSettingsNotesThePageThatWasOpen() {
        show()
        open(SettingsCategory.DOCK)
        // HOME or another app comes on top.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertThat(SettingsScrollMemory.restore(SystemClock.elapsedRealtime()).page).isEqualTo("dock")
    }

    @Test fun leavingFromTheMainPageNotesTheMainPage() {
        show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertThat(SettingsScrollMemory.restore(SystemClock.elapsedRealtime()).page).isNull()
    }

    @Test fun reopenedSoonSettingsStartsOnThePageThatWasOpen() {
        SettingsScrollMemory.remember(page = "icons", offset = 0, now = SystemClock.elapsedRealtime())
        show()
        compose.onNode(hasText(res.getString(R.string.settings_icons)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertExists()
        assertThat(tags("category-")).isEmpty()
        // And Back still leads to the main page.
        systemBack()
        assertOnMainPage()
    }

    @Test fun reopenedLaterSettingsStartsOnTheMainPage() {
        SettingsScrollMemory.remember(
            page = "icons", offset = 0, now = SystemClock.elapsedRealtime() - SettingsScrollMemory.WINDOW_MS - 1,
        )
        show()
        assertOnMainPage()
    }

    @Test fun aRememberedPageThisVersionDoesNotKnowIsTheMainPage() {
        SettingsScrollMemory.remember(page = "nonsense", offset = 40, now = SystemClock.elapsedRealtime())
        show()
        assertOnMainPage()
    }

    @Test fun anUnknownPageKeyIsTheMainPage() {
        assertThat(SettingsCategory.fromKey("nonsense")).isNull()
        assertThat(SettingsCategory.fromKey(null)).isNull()
        assertThat(SettingsCategory.fromKey("dock")).isEqualTo(SettingsCategory.DOCK)
    }
}
