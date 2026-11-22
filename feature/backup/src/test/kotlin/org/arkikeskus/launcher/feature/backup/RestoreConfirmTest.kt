package org.arkikeskus.launcher.feature.backup

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.data.backup.ConfirmedProfiles
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * What a restore asks before it replaces the launcher's state. A backup file holds one home profile and a
 * restore leaves that one: every other profile goes, with its home screen and its look. The question must say so
 * and name them, on the backup screen and in the "Open with" import alike; with one profile it is the question it
 * always was. Until the profiles have been read the question cannot be answered with Restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class RestoreConfirmTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val res: Resources = context.resources
    private val question = res.getString(R.string.backup_restore_confirm_msg)
    private var restores = 0
    private var dismissals = 0

    private fun resourcesIn(language: String): Resources {
        val config = Configuration(res.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(config).resources
    }

    @Test fun theProfilesARestoreDeletesAreAllButTheOneThatIsOn() {
        val profiles = listOf(HomeProfile(1, "Home"), HomeProfile(2, "Work"), HomeProfile(3, "Evening"))

        val lost = profilesARestoreDeletes(profiles, activeId = 2)
        assertThat(lost.names).containsExactly("Home", "Evening").inOrder()
        // What the restore is held to: the profile that is on and the ones that go, by id.
        assertThat(lost.confirmed).isEqualTo(ConfirmedProfiles(activeId = 2, otherIds = setOf(1L, 3L)))
        assertThat(profilesARestoreDeletes(listOf(HomeProfile(1, "Home")), activeId = 1).names).isEmpty()
    }

    @Test fun withOneProfileTheQuestionIsWhatItAlwaysWas() {
        assertThat(restoreConfirmText(res, emptyList())).isEqualTo(question)
    }

    @Test fun withOtherProfilesTheQuestionNamesTheOnesThatAreDeleted() {
        val text = restoreConfirmText(res, listOf("Work", "Evening"))

        assertThat(text).startsWith(question)
        assertThat(text).endsWith("This also deletes every other profile: Work, Evening.")
    }

    /**
     * The first profile has no name of its own until the user gives it one: everywhere it is shown as "Home",
     * in the user's language. The question names it the same way. With its stored name, which is empty, the
     * question ended "every other profile: ." (seen on a device, with the second profile on).
     */
    @Test fun anUnnamedProfileIsNamedAsItIsShownEverywhere() {
        assertThat(restoreConfirmText(res, listOf(""))).endsWith("This also deletes every other profile: Home.")
        assertThat(restoreConfirmText(res, listOf("", "Evening"))).endsWith("every other profile: Home, Evening.")
        assertThat(restoreConfirmText(resourcesIn("fi"), listOf(""))).endsWith("muut profiilit: Koti.")
    }

    /** A translation that lost the placeholder would warn without saying which profiles go. */
    @Test fun everyLanguageNamesTheDeletedProfiles() {
        for (language in listOf("de", "es", "fi", "fr", "pl", "pt", "ru", "sv", "tr")) {
            val translated = resourcesIn(language)
            val text = restoreConfirmText(translated, listOf("Work", "Evening"))
            assertWithMessage(language).that(text).contains("Work, Evening")
            assertWithMessage("$language is translated").that(translated.getString(R.string.backup_restore_confirm_profiles))
                .isNotEqualTo(res.getString(R.string.backup_restore_confirm_profiles))
        }
    }

    /** A launcher whose first profile is on and whose others have these names. */
    private fun profilesNamed(names: List<String>) =
        RestoreProfiles(activeId = 1, deleted = names.mapIndexed { i, name -> HomeProfile(i + 2L, name) })

    private fun showOnTheBackupScreen(deleted: List<String>?) = compose.setContent {
        RestoreConfirmDialog(profiles = deleted?.let(::profilesNamed), onConfirm = { restores++ }, onDismiss = { dismissals++ })
    }

    @Test fun theBackupScreensQuestionNamesTheDeletedProfiles() {
        showOnTheBackupScreen(listOf("Work"))

        compose.onNodeWithText(restoreConfirmText(res, listOf("Work"))).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.backup_restore_confirm_ok)).assertIsEnabled().performClick()
        assertThat(restores).isEqualTo(1)
    }

    @Test fun theBackupScreensQuestionWithOneProfileIsThePlainOne() {
        showOnTheBackupScreen(emptyList())

        compose.onNodeWithText(question).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.backup_restore_confirm_ok)).assertIsEnabled()
    }

    /** Not read yet: the question may not be answered before it can say what goes. Cancel still works. */
    @Test fun theBackupScreensRestoreWaitsUntilTheProfilesAreKnown() {
        showOnTheBackupScreen(null)

        compose.onNodeWithText(res.getString(R.string.backup_restore_confirm_ok)).assertIsNotEnabled()
        compose.onNodeWithText(res.getString(R.string.backup_cancel)).performClick()
        assertThat(dismissals).isEqualTo(1)
        assertThat(restores).isEqualTo(0)
    }

    /**
     * Review round 2: with many profiles to delete, on a small window at a large font, the question is taller
     * than its dialog. A text that does not scroll was cut there, and Restore could be tapped without the last
     * names ever having been on screen. The question scrolls, as the "Open with" import's does.
     */
    @Test fun theBackupScreensQuestionCanBeScrolledToItsLastName() {
        val seven = listOf("Work", "Evening", "Morning", "Weekend", "Travel", "Gym", "Night shift")
        showOnTheBackupScreen(seven)

        val text = restoreConfirmText(res, seven)
        compose.onNode(hasScrollAction() and (hasText(text) or hasAnyDescendant(hasText(text)))).assertExists()
    }

    /**
     * Review round 3. A profile is made a moment before Restore is tapped: the list of profiles has changed and
     * the question has not been drawn again yet. The restore is handed the profiles the question SHOWS, so the
     * new one is not among the confirmed and the restore refuses (it would have been deleted unnamed).
     */
    @Test fun theBackupScreensRestoreIsHeldToTheProfilesTheQuestionShows() {
        val shown = RestoreProfiles(activeId = 1, deleted = listOf(HomeProfile(2, "Work")))
        var profiles by mutableStateOf<RestoreProfiles?>(shown)
        val handed = mutableListOf<RestoreProfiles>()
        compose.setContent { RestoreConfirmDialog(profiles = profiles, onConfirm = { handed += it }, onDismiss = {}) }
        compose.waitForIdle()
        compose.onNodeWithText(restoreConfirmText(res, listOf("Work"))).assertIsDisplayed()

        // The list changes and the tap lands before the next frame. (The button's action is called as it
        // is: a tap made of touch events would run a frame of its own between its down and its up.)
        compose.mainClock.autoAdvance = false
        profiles = RestoreProfiles(activeId = 1, deleted = listOf(HomeProfile(2, "Work"), HomeProfile(3, "")))
        compose.onNodeWithText(res.getString(R.string.backup_restore_confirm_ok)).performSemanticsAction(SemanticsActions.OnClick)

        assertThat(handed).containsExactly(shown)
    }
}
