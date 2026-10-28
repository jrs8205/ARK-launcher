package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
    // Before the wiring: its app list is queried eagerly. Robolectric resolves a fake app's label to
    // its package name, so the drawer shows this app as "com.mail".
    private val mailApp = "com.mail".also { installFakeApp(context, it, "Mail") }
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

        compose.onNodeWithText(context.getString(R.string.folder_delete_title)).assertExists()
        assertThat(viewModel.uiState.value.folders.map { it.name }).containsExactly("Tools")
        compose.onNodeWithText(context.getString(R.string.folder_delete_confirm)).performClick()
        awaitState("the folder is gone") { it.folders.isEmpty() }
    }

    /** The long-press is the only way to the editor, so TalkBack must say what it does. */
    @Test fun theFolderTileTellsTalkBackWhatALongPressDoes() {
        showWithFolder()
        val editFolder = context.getString(R.string.folder_edit)

        compose.onNodeWithText("Tools").assert(
            SemanticsMatcher("long-press labelled '$editFolder'") {
                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == editFolder
            },
        )
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

    /** The editor moves its folder a step among the folders; the first cannot go earlier. */
    @Test fun theEditorMovesAFolderLater() {
        runBlocking {
            wiring.settingsRepository.createDrawerFolder("Tools")
            wiring.settingsRepository.createDrawerFolder("Games")
        }
        show()
        awaitState("both folders are listed") { it.folders.size == 2 }
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText(context.getString(R.string.folder_move_label)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.folder_move_earlier)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.folder_move_later)).performClick()

        awaitState("Tools follows Games") { it.folders.map { f -> f.name } == listOf("Games", "Tools") }
        settle()
        compose.onNodeWithContentDescription(context.getString(R.string.folder_move_later)).assertIsNotEnabled()
    }

    /** One folder has nowhere to go: no move row at all. */
    @Test fun theEditorOffersNoMovesForASingleFolder() {
        showWithFolder()
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText(addApps()).assertExists()
        compose.onNodeWithText(context.getString(R.string.folder_move_label)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.folder_move_later)).assertDoesNotExist()
    }

    /** Sorted A-Z the order is not the user's to change, so the editor offers no moves. */
    @Test fun theEditorHidesMovesWhileFoldersSortByName() {
        runBlocking { wiring.settingsRepository.setSortDrawerFolders(true) }
        showWithFolder()
        awaitState("the setting reached the drawer") { it.sortDrawerFolders }
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText(addApps()).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.folder_move_later)).assertDoesNotExist()
    }

    /** Issue #17: with "Show folder names" off the drawer's folder tiles carry no name. */
    @Test fun theFolderTileHidesItsNameWhenTheSettingIsOff() {
        runBlocking {
            wiring.settingsRepository.setShowDrawerFolderNames(false)
            wiring.settingsRepository.createDrawerFolder("Tools")
        }
        show()
        awaitState("the folder is listed without its name") { it.folders.size == 1 && !it.showFolderNames }
        settle()

        compose.onNodeWithText("Tools").assertDoesNotExist()

        runBlocking { wiring.settingsRepository.setShowDrawerFolderNames(true) }
        awaitText("Tools")
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

    private fun appInfo() = context.getString(R.string.app_info)
    private fun editTitle() = context.getString(org.arkikeskus.launcher.ui.R.string.edit_title)

    /** The drawer on screen with its one app listed. */
    private fun showWithApp() {
        show()
        awaitText(mailApp)
    }

    /** The drawer's own long-press: a still hold until the icon lifts (the menu opens then), release. */
    private fun holdIcon() {
        compose.onNodeWithText(mailApp).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        settle()
        compose.onNodeWithText(mailApp).performTouchInput { up() }
        settle()
    }

    /** Issue #17: the Add-apps picker filters its list by name as you type, and a pick survives
     *  the filter. */
    @Test fun theAddAppsPickerSearchesByName() {
        showWithFolder()
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText(addApps()).performClick()
        settle()
        assertThat(compose.onAllNodesWithTag(ADD_APPS_ROW_TAG).fetchSemanticsNodes()).hasSize(1)

        compose.onNode(hasTestTag(ADD_APPS_SEARCH_TAG)).performTextInput("zq")
        settle()
        assertThat(compose.onAllNodesWithTag(ADD_APPS_ROW_TAG).fetchSemanticsNodes()).isEmpty()

        compose.onNode(hasTestTag(ADD_APPS_SEARCH_TAG)).performTextClearance()
        compose.onNode(hasTestTag(ADD_APPS_SEARCH_TAG)).performTextInput("MAIL")
        settle()
        compose.onAllNodesWithTag(ADD_APPS_ROW_TAG)[0].performClick()
        compose.onNode(hasTestTag(ADD_APPS_SEARCH_TAG)).performTextInput("zq")
        settle()
        compose.onNodeWithText(context.getString(R.string.folder_add)).performClick()

        awaitState("the app joined the folder") { it.folders.single().apps.map { a -> a.packageName } == listOf(mailApp) }
    }

    /** The app menu and the Edit dialog are windows of their own too: they go with the drawer. */
    @Test fun closingTheDrawerTakesTheAppMenu() {
        showWithApp()
        holdIcon()
        compose.onNodeWithText(appInfo()).assertExists()

        drawerOpen.value = false
        settle()

        compose.onNodeWithText(appInfo()).assertDoesNotExist()
    }

    @Test fun closingTheDrawerTakesTheEditDialog() {
        showWithApp()
        holdIcon()
        compose.onNodeWithText(context.getString(R.string.rename)).performClick()
        settle()
        compose.onNodeWithText(editTitle()).assertExists()

        drawerOpen.value = false
        settle()

        compose.onNodeWithText(editTitle()).assertDoesNotExist()
    }

    @Test fun aClosingDrawerOpensNoAppMenu() {
        showWithApp()
        drawerOpen.value = false
        settle()

        holdIcon()

        compose.onNodeWithText(appInfo()).assertDoesNotExist()
    }

    /** The drawer stays composed while it slides away, so a tap can still land on a tile then: no
     *  card may open over the home screen (the card is a window of its own, it would stay). */
    @Test fun aClosingDrawerOpensNoFolderCard() {
        showWithFolder()
        drawerOpen.value = false
        settle()

        compose.onNodeWithText("Tools").performClick()
        settle()

        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertDoesNotExist()
    }

    @Test fun aClosingDrawerOpensNoFolderEditor() {
        showWithFolder()
        drawerOpen.value = false
        settle()

        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText(addApps()).assertDoesNotExist()
    }

    /** A card already open goes with the drawer, whatever closed it. */
    @Test fun closingTheDrawerTakesTheFolderCard() {
        showWithFolder()
        compose.onNodeWithText("Tools").performClick()
        settle()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()

        drawerOpen.value = false
        settle()

        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertDoesNotExist()
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
        compose.onNode(hasSetTextAction()).performTextInput("zq")
        settle() // the query is on screen; the looper's clock stands still, so the debounce has not run
        assertThat(viewModel.uiState.value.query).isEqualTo("zq")
        compose.onNodeWithText(noResults, useUnmergedTree = true).assertDoesNotExist()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)) // the debounce elapses; the search runs on its own thread
        awaitText(noResults, unmerged = true) // nothing on the test device matches "zq"

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
