package org.arkikeskus.launcher.feature.backup

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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

        assertThat(profilesARestoreDeletes(profiles, activeId = 2)).containsExactly("Home", "Evening").inOrder()
        assertThat(profilesARestoreDeletes(listOf(HomeProfile(1, "Home")), activeId = 1)).isEmpty()
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

    private fun showOnTheBackupScreen(deleted: List<String>?) = compose.setContent {
        RestoreConfirmDialog(deletedProfiles = deleted, onConfirm = { restores++ }, onDismiss = { dismissals++ })
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
}
