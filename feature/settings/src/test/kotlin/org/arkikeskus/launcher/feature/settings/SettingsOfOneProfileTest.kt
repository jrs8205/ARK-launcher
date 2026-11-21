package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Review round 8. A Settings page shows one home profile's values, and what it has open is of that profile: a
 * drawer folder's page with its "Delete?", a dialog, a name half typed. The page tells its view model which
 * profile it shows NOW, so a question left open over a switch was answered for the profile that had come on, and a
 * copy of a profile has drawer folders with the same ids: "Delete" asked about one profile's folder removed the
 * other's. With another profile, whatever was open is closed; the page itself stays where it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SettingsOfOneProfileTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val res = RuntimeEnvironment.getApplication().resources
    private val recorded = RecordedActions()
    private var settings by mutableStateOf(LauncherSettings(homeProfileId = 1))

    // The same folder id in both profiles, as in a profile and its copy.
    private val lists = SettingsLists(
        drawerFolders = listOf(DrawerFolder(1, "Tools", emptyList())),
        homeProfiles = listOf(HomeProfile(1, "Home"), HomeProfile(2, "Copy")),
    )

    private fun show(page: SettingsCategory) {
        compose.setContent {
            SettingsContent(
                s = settings,
                lists = lists,
                actions = recorded.actions,
                blurAvailability = BlurAvailability.AVAILABLE,
                openPage = page.key,
                backupScreen = { Box(Modifier) },
            )
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = res.getString(id)

    /**
     * Review round 9. The settings and the drawer's folders are read separately; after a switch the new profile's
     * settings can be there before its folders. The page showed the OLD profile's folders then: one opened in
     * that moment stayed open when the new profile had a folder of the same id, and its new name went to that
     * one. The folders are taken only while they are of the profile the page shows.
     */
    @Test fun theFoldersPageTakesOnlyTheFoldersOfTheProfileItShows() {
        val ofFirst = FoldersOfProfile(profile = 1, folders = listOf(DrawerFolder(1, "Tools", emptyList())))

        assertThat(drawerFoldersShown(ofFirst, profile = 1)).isEqualTo(ofFirst.folders)
        // The second profile's settings are there, the folders are still the first one's: none yet.
        assertThat(drawerFoldersShown(ofFirst, profile = 2)).isNull()
        assertThat(drawerFoldersShown(null, profile = 1)).isNull()
        assertThat(drawerFoldersShown(ofFirst, profile = null)).isNull()
    }

    private fun calls(name: String) = recorded.calls.filter { it.first == name }

    @Test fun aFoldersDeleteQuestionGoesWhenAnotherProfileComesOnAndDeletesNothingOfIt() {
        show(SettingsCategory.FOLDERS)
        compose.onNodeWithText(text(R.string.settings_drawer_folders)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tools").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.settings_folder_delete)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.settings_folder_delete_title)).assertExists()

        // The copy comes on while the question is open; it has a folder of the same id.
        settings = settings.copy(homeProfileId = 2)
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.settings_folder_delete_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_folder_delete_confirm)).assertDoesNotExist()
        assertThat(calls("deleteDrawerFolder")).isEmpty()
        // The folders' screen is still the one shown: the list, with the copy's folder to open anew.
        compose.onNodeWithText("Tools").assertExists()
        compose.onNodeWithText(text(R.string.settings_folder_delete)).assertDoesNotExist()
    }

    @Test fun aNewProfilesDialogGoesWhenAnotherProfileComesOnAndMakesNoCopyOfIt() {
        show(SettingsCategory.PROFILES)
        compose.onNodeWithText(text(R.string.settings_profile_new)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists()

        settings = settings.copy(homeProfileId = 2)
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_profile_create)).assertDoesNotExist()
        assertThat(calls("createHomeProfile")).isEmpty()
        // The Profiles page is still the one shown.
        compose.onNodeWithText(text(R.string.settings_profile_new)).assertExists()
    }
}
