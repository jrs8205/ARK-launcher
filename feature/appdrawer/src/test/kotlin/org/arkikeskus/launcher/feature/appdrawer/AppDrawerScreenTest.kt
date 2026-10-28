package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * What the drawer leaves behind when it goes: HOME takes its windows (a folder's sheet, the Edit
 * dialog) with it, and ANY way of closing it — a pull-down, Back from an app — clears the query (and
 * the search focus with it, so the keyboard never stays up over the home screen) so the drawer
 * reopens fresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AppDrawerScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val testScope = TestScope()
    private val wiring = DrawerWiring(context, testScope)
    private val viewModel = wiring.viewModel()
    private val homeSignals = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val drawerOpen = mutableStateOf(true)

    @After fun tearDown() {
        wiring.clear(viewModel)
        wiring.close()
    }

    private fun show() {
        compose.setContent {
            AppDrawerScreen(
                onClose = {},
                onOpenSettings = {},
                homeSignals = homeSignals,
                drawerOpen = drawerOpen.value,
                viewModel = viewModel,
            )
        }
        settle()
    }

    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** The ViewModel's stores (Room, DataStore) and the search (Dispatchers.Default) answer from
     *  their own threads: waits, bounded, for [text] to be on screen instead of assuming a fixed
     *  number of idle rounds is enough. */
    private fun awaitText(text: String, unmerged: Boolean = false) {
        val deadline = System.currentTimeMillis() + 10_000
        while (compose.onAllNodesWithText(text, useUnmergedTree = unmerged).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for '$text'" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun addApps() = context.getString(R.string.folder_add_apps)

    private fun showWithFolder(name: String = "Tools") {
        runBlocking { wiring.settingsRepository.createDrawerFolder(name) }
        show()
        awaitText(name)
    }

    /** A tap shows the folder's apps in a card by its icon — nothing on it changes the folder. */
    @Test fun aTapShowsTheFolderWithoutItsEditor() {
        showWithFolder()
        compose.onNodeWithText("Tools").performClick()
        settle()

        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
        compose.onNodeWithText(addApps()).assertDoesNotExist()
    }

    /** The editor (name, members, Add apps, Delete) is a long-press away, never a tap. */
    @Test fun aLongPressOpensTheFolderEditor() {
        showWithFolder()
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText(addApps()).assertExists()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertDoesNotExist()
    }

    @Test fun homeTakesTheFolderCardWithTheDrawer() {
        showWithFolder()
        compose.onNodeWithText("Tools").performClick()
        settle()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()

        compose.runOnIdle { homeSignals.tryEmit(true) }
        settle()

        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertDoesNotExist()
    }

    /** The editor's Delete asks first: the folder stays until the question's own Delete. */
    @Test fun deletingAFolderAsksFirst() {
        showWithFolder()
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText(context.getString(R.string.folder_delete)).performClick()
        settle()

        assertThat(viewModel.uiState.value.folders.map { it.name }).containsExactly("Tools")
        compose.onNodeWithText(context.getString(R.string.folder_delete_confirm)).performClick()
        awaitState("the folder is gone") { it.folders.isEmpty() }
    }

    /** The setting sorts the folders by name, case aside; off, they keep the user's order. */
    @Test fun foldersSortByNameWhenTheSettingIsOn() {
        runBlocking {
            wiring.settingsRepository.createDrawerFolder("Tools")
            wiring.settingsRepository.createDrawerFolder("games")
        }
        show()
        awaitState("both folders are listed") { it.folders.size == 2 }
        assertThat(viewModel.uiState.value.folders.map { it.name }).containsExactly("Tools", "games").inOrder()

        runBlocking { wiring.settingsRepository.setSortDrawerFolders(true) }
        awaitState("the folders are sorted") { it.folders.map { f -> f.name } == listOf("games", "Tools") }
    }

    private fun awaitState(what: String, condition: (AppDrawerUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition(viewModel.uiState.value)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting until $what" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test fun homeTakesTheFolderSheetWithTheDrawer() {
        showWithFolder()
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText(addApps()).assertExists()

        compose.runOnIdle { homeSignals.tryEmit(true) }
        settle()

        compose.onNodeWithText(addApps()).assertDoesNotExist()
    }

    /** The first letter of a search used to flash "No results": the query reaches the screen at
     *  once while the results (debounced, then searched) still answer the previous query. */
    @Test fun noResultsWaitsForTheResults() {
        show()
        val noResults = context.getString(R.string.search_no_results)
        compose.onNode(hasSetTextAction()).performTextInput("c")
        settle() // the query is on screen; the looper's clock stands still, so the debounce has not run
        assertThat(viewModel.uiState.value.query).isEqualTo("c")
        compose.onNodeWithText(noResults, useUnmergedTree = true).assertDoesNotExist()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)) // the debounce elapses; the search runs on its own thread
        awaitText(noResults, unmerged = true) // no app is called "c" on the test device

        assertThat(viewModel.uiState.value.searchPending).isFalse()
    }

    /** The focus goes the same way as on HOME (the keyboard with it); the query is what a test can see. */
    @Test fun closingTheDrawerDropsTheQuery() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("ch")
        settle()
        assertThat(viewModel.uiState.value.query).isEqualTo("ch")

        // Pulled down, or closed behind a launched app: no HOME press.
        drawerOpen.value = false
        settle()

        assertThat(viewModel.uiState.value.query).isEmpty()
    }
}
