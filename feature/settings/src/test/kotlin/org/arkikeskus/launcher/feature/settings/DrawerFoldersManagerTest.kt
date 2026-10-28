package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.os.Process
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings → App drawer → Folders (issue #17): the folders with their app counts, and a folder's
 * page where the apps it could hold have a switch each, narrowed by a search; renaming and deleting
 * (asked first) reach the callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerFoldersManagerTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources

    private fun app(name: String) = AppItem(
        packageName = "com.${name.lowercase()}", className = "$name.Main", user = Process.myUserHandle(),
        userSerial = 0, label = name,
    )

    private val mail = app("Mail")
    private val maps = app("Maps")
    private val slack = app("Slack")
    private val secret = app("Secret")
    private val tools = DrawerFolder(id = 1, name = "Tools", appKeys = listOf(mail.key))
    private val chat = DrawerFolder(id = 2, name = "Chat", appKeys = listOf(slack.key))

    private val renamed = mutableListOf<Pair<Long, String>>()
    private val deleted = mutableListOf<Long>()
    private val members = mutableListOf<Triple<Long, String, Boolean>>()
    private var created = 0

    private fun show(folders: List<DrawerFolder> = listOf(tools, chat)) {
        compose.setContent {
            DrawerFoldersManager(
                folders = folders,
                apps = listOf(mail, maps, secret, slack),
                hiddenKeys = setOf(secret.key),
                onCreate = { created++ },
                onRename = { id, name -> renamed += id to name },
                onDelete = { deleted += it },
                onSetMember = { id, key, member -> members += Triple(id, key, member) },
                onBack = {},
            )
        }
        compose.waitForIdle()
    }

    private fun appsCount(n: Int) = res.getQuantityString(R.plurals.settings_folder_apps_count, n, n)

    private fun appRow(label: String) = compose.onNode(hasTestTag(FOLDER_MANAGER_APP_ROW_TAG) and hasText(label))

    @Test fun listsTheFoldersWithTheirAppCounts() {
        show()

        compose.onNodeWithText("Tools").assertExists()
        compose.onNodeWithText("Chat").assertExists()
        assertThat(compose.onAllNodesWithTag(FOLDER_MANAGER_FOLDER_ROW_TAG).fetchSemanticsNodes()).hasSize(2)
        assertThat(
            compose.onAllNodes(hasTestTag(FOLDER_MANAGER_FOLDER_ROW_TAG) and hasText(appsCount(1))).fetchSemanticsNodes(),
        ).hasSize(2)
    }

    @Test fun noFoldersSaysSoAndOffersToCreateOne() {
        show(folders = emptyList())

        compose.onNodeWithText(res.getString(R.string.settings_drawer_folders_empty)).assertExists()
        compose.onNodeWithText(res.getString(R.string.settings_new_drawer_folder)).performClick()
        assertThat(created).isEqualTo(1)
    }

    /** Offered: the folder's own members (on) and the free apps (off); not an app in another
     *  folder, nor a hidden one. */
    @Test fun aFolderPageOffersItsMembersAndTheFreeApps() {
        show()
        compose.onNodeWithText("Tools").performClick()
        compose.waitForIdle()

        appRow("Mail").assertIsOn()
        appRow("Maps").assertIsOff()
        appRow("Slack").assertDoesNotExist()
        appRow("Secret").assertDoesNotExist()
    }

    @Test fun theSearchNarrowsTheAppsAndASwitchReachesTheCallback() {
        show()
        compose.onNodeWithText("Tools").performClick()
        compose.waitForIdle()

        compose.onNode(hasTestTag(FOLDER_MANAGER_SEARCH_TAG)).performTextInput("map")
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag(FOLDER_MANAGER_APP_ROW_TAG).fetchSemanticsNodes()).hasSize(1)

        appRow("Maps").performClick()
        assertThat(members).containsExactly(Triple(1L, maps.key, true))

        compose.onNode(hasTestTag(FOLDER_MANAGER_SEARCH_TAG)).performTextInput("zq")
        compose.waitForIdle()
        compose.onNodeWithText(res.getString(R.string.settings_folder_no_results)).assertExists()
    }

    @Test fun renamingReachesTheCallback() {
        show()
        compose.onNodeWithText("Tools").performClick()
        compose.waitForIdle()

        compose.onNode(hasTestTag(FOLDER_MANAGER_NAME_TAG)).performTextReplacement("Tools2")

        assertThat(renamed).containsExactly(1L to "Tools2")
    }

    @Test fun deletingAsksFirst() {
        show()
        compose.onNodeWithText("Tools").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(res.getString(R.string.settings_folder_delete)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(res.getString(R.string.settings_folder_delete_title)).assertExists()
        assertThat(deleted).isEmpty()

        compose.onNodeWithText(res.getString(R.string.settings_folder_delete_confirm)).performClick()
        assertThat(deleted).containsExactly(1L)
    }
}
