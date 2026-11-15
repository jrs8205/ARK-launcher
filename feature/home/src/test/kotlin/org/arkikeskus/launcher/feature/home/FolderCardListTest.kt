package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.ADD_APPS_ROW_TAG
import org.arkikeskus.launcher.ui.ADD_APPS_SEARCH_TAG
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home folder's editor (discussion #16): "Add apps" opens a list of the apps the folder does not hold,
 * several can be ticked and added at once; "Sort A-Z" puts the folder's apps in the order of their names.
 * Both live in the editor only: looking into a folder changes nothing by accident.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FolderCardListTest {
    @get:Rule val compose = createComposeRule()

    private val user = Process.myUserHandle()
    private fun app(name: String) = AppItem("com.${name.lowercase()}", "Main", user, 0L, name)

    private val mail = app("Mail")
    private val maps = app("Maps")
    private val calendar = app("Calendar")
    private val camera = app("Camera")
    private val notes = app("Notes")
    private val folder = PlacedFolder(id = 7, name = "Tools", apps = listOf(mail, maps), page = 0, cellX = 0, cellY = 0)

    private val added = mutableListOf<List<AppItem>>()
    private var sorts = 0

    private fun show(locked: Boolean = false) {
        compose.setContent {
            LauncherTheme {
                Box(Modifier.fillMaxSize()) {
                    FolderCard(
                        folder = folder,
                        badges = emptyMap(),
                        badgeShowCount = false,
                        badgeScale = 1f,
                        locked = locked,
                        dragController = HomeDragController(),
                        onRename = {},
                        onAppClick = {},
                        onRenameApp = {},
                        onRemoveFromFolder = {},
                        onPinShortcut = {},
                        onReorder = { _, _ -> },
                        onDropOnHome = { _, _, _, _ -> },
                        onDropOnDock = { _, _ -> },
                        onDropOnRemove = {},
                        onDragOutEnd = {},
                        onDismiss = {},
                        candidates = listOf(calendar, camera, notes),
                        onAddApps = { added += it },
                        onSortByName = { sorts++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openEditor() {
        compose.onNodeWithContentDescription("Edit folder").performClick()
        compose.waitForIdle()
    }

    @Test fun lookingIntoAFolderOffersNeitherAction() {
        show()

        compose.onNodeWithText("Add apps").assertDoesNotExist()
        compose.onNodeWithText("Sort A–Z").assertDoesNotExist()
    }

    @Test fun theEditorOffersBoth() {
        show()
        openEditor()

        compose.onNodeWithText("Add apps").assertExists()
        compose.onNodeWithText("Sort A–Z").assertExists()
    }

    @Test fun sortingAsksForTheFolderToBeSortedAndStaysInTheEditor() {
        show()
        openEditor()

        compose.onNodeWithText("Sort A–Z").performClick()

        assertThat(sorts).isEqualTo(1)
        compose.onNodeWithText("Add apps").assertExists()
    }

    @Test fun theListOffersTheAppsTheFolderDoesNotHold() {
        show()
        openEditor()

        compose.onNodeWithText("Add apps").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(ADD_APPS_ROW_TAG).assertCountEquals(3)
        compose.onNodeWithText("Calendar").assertExists()
        compose.onNodeWithText("Camera").assertExists()
        compose.onNodeWithText("Notes").assertExists()
        // Nothing is ticked yet, so there is nothing to add.
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test fun severalTickedAppsAreAddedAtOnceInTheOrderTheyWereTicked() {
        show()
        openEditor()
        compose.onNodeWithText("Add apps").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Notes").performClick()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()

        assertThat(added).containsExactly(listOf(notes, calendar))
        // The list is closed; the editor is still there.
        compose.onAllNodesWithTag(ADD_APPS_ROW_TAG).assertCountEquals(0)
        compose.onNodeWithText("Sort A–Z").assertExists()
    }

    @Test fun cancellingAddsNothing() {
        show()
        openEditor()
        compose.onNodeWithText("Add apps").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertThat(added).isEmpty()
        compose.onAllNodesWithTag(ADD_APPS_ROW_TAG).assertCountEquals(0)
    }

    @Test fun theSearchNarrowsTheListAndKeepsWhatWasTicked() {
        show()
        openEditor()
        compose.onNodeWithText("Add apps").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Notes").performClick()
        compose.onNodeWithTag(ADD_APPS_SEARCH_TAG).performTextInput("ca")
        compose.waitForIdle()
        compose.onAllNodesWithTag(ADD_APPS_ROW_TAG).assertCountEquals(2)
        compose.onAllNodesWithText("Notes").assertCountEquals(0)
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithText("Add").performClick()

        assertThat(added).containsExactly(listOf(notes, camera))
    }
}
