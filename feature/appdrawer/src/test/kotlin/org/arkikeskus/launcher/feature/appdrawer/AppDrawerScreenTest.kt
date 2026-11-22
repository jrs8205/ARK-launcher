package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
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
import org.arkikeskus.launcher.ui.ADD_APPS_ROW_TAG
import org.arkikeskus.launcher.ui.HomeDragController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.moveBy
import kotlinx.coroutines.flow.first
import org.arkikeskus.launcher.ui.ADD_APPS_SEARCH_TAG
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import android.os.Process
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
    private val mapsApp = "com.maps".also { installFakeApp(context, it, "Maps") }
    private val notesApp = "com.notes".also { installFakeApp(context, it, "Notes") }
    private val docsApp = "com.docs".also { installFakeApp(context, it, "Docs") }
    private val wiring = DrawerWiring(context, testScope)
    private val viewModel = wiring.viewModel()
    private val homeSignals = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val drawerOpen = mutableStateOf(true)
    private val drag = HomeDragController()
    private var dragOuts = 0

    @After fun tearDown() {
        wiring.clear(viewModel)
        wiring.close()
    }

    private fun show() {
        compose.setContent {
            // An app's menu asks Android for its shortcuts and widgets before it shows. Asked on another
            // thread, the answer comes back on that thread under the rule the tests had then and the screen is then worked on
            // from two threads at once (the home screen's menu test failed so once). Here it asks at once.
            androidx.compose.runtime.CompositionLocalProvider(
                org.arkikeskus.launcher.ui.LocalBackgroundDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined,
            ) {
                AppDrawerScreen(
                    onClose = {},
                    onOpenSettings = {},
                    homeSignals = homeSignals,
                    drawerOpen = drawerOpen.value,
                    dragController = drag,
                    onDragOutStart = { dragOuts++ },
                    viewModel = viewModel,
                )
            }
        }
        settle()
        // The screen's state is put together from the app list, the settings and the home rows, and until
        // each of them has answered once it is the empty state it starts with: what a test then types or
        // changes is not in it. The settings answer from the store's own thread, which idling this one does
        // not wait for. A test that read the state right after typing failed in a run of the whole test task
        // ("expected: ch, but was an empty string"): the settings had not answered yet.
        awaitState("the drawer has its first state") { it.apps.isNotEmpty() }
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

    /** Issue #32: a drawer folder's apps stand in the order they were added, which soon looks like no order.
     *  The editor sorts them by name on request, as a home folder's editor does. */
    @Test fun theEditorSortsTheFoldersAppsByName() {
        val id = runBlocking { wiring.settingsRepository.createDrawerFolder("Tools") }
        show()
        val members = listOf(notesApp, mailApp, docsApp)
        awaitState("the apps are listed") { s -> members.all { p -> s.apps.any { it.packageName == p } } }
        val byPackage = viewModel.uiState.value.apps.associateBy { it.packageName }
        runBlocking { wiring.settingsRepository.addAppsToDrawerFolder(id, members.map { byPackage.getValue(it).key }) }
        awaitState("the folder holds the three as they were added") { s ->
            s.folders.firstOrNull()?.apps?.map { it.packageName } == members
        }
        settle()
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        awaitText(addApps())

        compose.onNodeWithText(context.getString(R.string.folder_sort_by_name)).performClick()

        awaitState("the folder's apps are in the order of their names") { s ->
            s.folders.firstOrNull()?.apps?.map { it.packageName } == listOf(docsApp, mailApp, notesApp)
        }
        // The editor stays open: sorting is one of its actions, not a way out of it.
        compose.onNodeWithText(addApps()).assertExists()
    }

    // --- Discussion #34: the search can find hidden apps -----------------------------------------------------

    /** The drawer with the mail app hidden; returns its key once the lists are without it. */
    private fun showWithMailHidden(): String {
        show()
        awaitState("the app is listed") { s -> s.apps.any { it.packageName == mailApp } }
        val mail = viewModel.uiState.value.apps.first { it.packageName == mailApp }
        runBlocking { wiring.settingsRepository.setAppHidden(mail.key, true) }
        awaitState("the hidden app left the list") { s -> s.apps.none { it.packageName == mailApp } }
        return mail.key
    }

    private fun search(query: String) {
        viewModel.onQueryChange(query)
        awaitState("the search for '$query' is answered") { it.query == query && !it.searchPending }
        settle()
    }

    /** As it always was: a hidden app is hidden from the search too. */
    @Test fun aHiddenAppIsNotFoundByTheSearchUnlessThatIsAskedFor() {
        showWithMailHidden()

        search("mail")

        assertThat(viewModel.uiState.value.apps.map { it.packageName }).doesNotContain(mailApp)
        compose.onNodeWithText(mailApp).assertDoesNotExist()
    }

    /** Asked for in Settings, the search finds the hidden app by its name; the app list still does not show it,
     *  and its menu does not offer to hide what is hidden already. */
    @Test fun askedForTheSearchFindsAHiddenAppThatTheListStillDoesNotShow() {
        showWithMailHidden()
        runBlocking { wiring.settingsRepository.setSearchHiddenApps(true) }

        search("mail")

        assertThat(viewModel.uiState.value.apps.map { it.packageName }).containsExactly(mailApp)
        compose.onNodeWithText(mailApp).performTouchInput { longClick() }
        awaitText(appInfo())
        compose.onNodeWithText(context.getString(R.string.hide_app)).assertDoesNotExist()
        // An app that is not hidden keeps the row.
        compose.onNodeWithText(appInfo()).assertExists()

        viewModel.onQueryChange("")
        awaitState("the idle drawer") { it.query.isEmpty() && it.apps.isNotEmpty() }
        assertThat(viewModel.uiState.value.apps.map { it.packageName }).doesNotContain(mailApp)
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

    /**
     * Review round 7. The editor remembered its folder by the id alone and took the profile anew from whatever
     * state it was drawn from. A copy of a profile has folders with the same ids: with the switch to the copy
     * made while the editor's "Delete?" was open, the editor showed the copy's folder with that question still
     * up, and "Delete" removed the folder of the profile the user had never asked about. What is open is of the
     * profile it was opened on, and goes when another one comes on.
     */
    @Test fun aFoldersEditorAndItsQuestionGoWhenAnotherProfileComesOn() {
        showWithFolder()
        val copy = runBlocking {
            (wiring.homeProfiles.create("Copy", copyOf = 1) as HomeProfileRepository.Created.Done).profile.id
        }
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText(context.getString(R.string.folder_delete)).performClick()
        settle()
        compose.onNodeWithText(context.getString(R.string.folder_delete_title)).assertExists()

        // The switch that had been waiting is made while the question is open.
        runBlocking { assertThat(wiring.homeProfiles.switchTo(copy)).isTrue() }
        awaitState("the copy is on") { it.homeProfileId == copy && it.folders.size == 1 }
        settle()

        compose.onNodeWithText(context.getString(R.string.folder_delete_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.folder_delete_confirm)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.folder_delete)).assertDoesNotExist()
        // The copy's folder, of the same id, is as it was; held again, it opens its own editor.
        assertThat(viewModel.uiState.value.folders.map { it.name }).containsExactly("Tools")
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()
        compose.onNodeWithText(context.getString(R.string.folder_delete)).assertExists()
    }

    /**
     * Review round 9. And a folder that is being dragged out of the drawer is of the profile it was lifted in.
     * The tile's gesture lived as long as the folder's id; a copy of a profile has a folder of the same id, so
     * over a switch made mid-drag the gesture went on: the finger still showed the folder it had lifted, the
     * drop was the other profile's folder, and that profile's home screen took it. The drag ends with the
     * switch and the release places nothing.
     */
    @Test fun aFolderBeingDraggedIsLetGoWhenAnotherProfileComesOnAndItsReleasePlacesNothing() {
        val id = runBlocking { wiring.settingsRepository.createDrawerFolder("Tools") }
        show()
        val members = listOf(mailApp, mapsApp)
        awaitState("the apps are listed") { s -> members.all { p -> s.apps.any { it.packageName == p } } }
        val byPackage = viewModel.uiState.value.apps.associateBy { it.packageName }
        runBlocking { wiring.settingsRepository.addAppsToDrawerFolder(id, members.map { byPackage.getValue(it).key }) }
        awaitState("the folder holds the two") { s -> s.folders.firstOrNull()?.apps?.size == 2 }
        val copy = runBlocking {
            (wiring.homeProfiles.create("Copy", copyOf = 1) as HomeProfileRepository.Created.Done).profile.id
        }
        settle()
        // The whole window is home grid: wherever the finger is let go, it is over a cell.
        drag.gridBounds = Rect(0f, 0f, 10_000f, 10_000f)
        drag.columns = 4
        drag.rows = 5
        val longPress = 2_000L

        compose.onNodeWithText("Tools").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(longPress)
        compose.waitForIdle()
        compose.onNodeWithText("Tools").performTouchInput { repeat(10) { moveBy(Offset(0f, 12f), delayMillis = 16) } }
        compose.waitForIdle()
        assertThat(drag.draggedDrawerFolder?.name).isEqualTo("Tools")
        assertThat(drag.moving).isTrue()

        // The switch that had been waiting is made while the folder is in the air.
        runBlocking { assertThat(wiring.homeProfiles.switchTo(copy)).isTrue() }
        awaitState("the copy is on") { it.homeProfileId == copy && it.folders.firstOrNull()?.apps?.size == 2 }
        settle()

        assertThat(drag.draggedDrawerFolder).isNull()
        assertThat(drag.isDragging).isFalse()
        compose.onNodeWithText("Tools").performTouchInput { up() }
        settle()
        Thread.sleep(300)
        settle()
        val rows = runBlocking { wiring.homeLayoutRepository.profilesWithRows() }
        assertThat(rows).isEmpty()
    }

    /** The same for an app's menu: opened on one profile's drawer, it is gone when another profile comes on. */
    @Test fun anAppsMenuGoesWhenAnotherProfileComesOn() {
        show()
        awaitText(mailApp)
        val copy = runBlocking {
            (wiring.homeProfiles.create("Copy", copyOf = 1) as HomeProfileRepository.Created.Done).profile.id
        }
        compose.onNodeWithText(mailApp).performTouchInput { longClick() }
        awaitText(appInfo())

        runBlocking { assertThat(wiring.homeProfiles.switchTo(copy)).isTrue() }
        awaitState("the copy is on") { it.homeProfileId == copy }
        settle()

        compose.onNodeWithText(appInfo()).assertDoesNotExist()
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

    /** A keyboard or a D-pad reaches the folder as it reaches a button: the tile takes focus and Enter opens its
     *  card. The tile's touches are a pointer gesture of its own (it can be dragged out), which answers no key. */
    @Test fun theFolderTileTakesFocusAndEnterOpensItsCard() {
        showWithFolder()

        compose.onNodeWithText("Tools").requestFocus()
        settle()
        compose.onNodeWithText("Tools").assertIsFocused()
        compose.onNodeWithText("Tools").performKeyInput { pressKey(Key.Enter) }
        settle()

        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
        compose.onNodeWithText(addApps()).assertDoesNotExist()
    }

    /** The key held down is the hold: the editor, which has no other way in. Its release opens no card after it. */
    @Test fun aHeldEnterOnTheFolderTileOpensItsEditor() {
        showWithFolder()

        compose.onNodeWithText("Tools").requestFocus()
        settle()
        compose.onNodeWithText("Tools").performKeyInput { keyDown(Key.Enter) }
        compose.mainClock.advanceTimeBy(1_000)
        settle()

        compose.onNodeWithText(addApps()).assertExists()
        // The tile is the first "Tools" on screen; the editor's name field is the other.
        compose.onAllNodesWithText("Tools").onFirst().performKeyInput { keyUp(Key.Enter) }
        settle()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertDoesNotExist()
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

    /** Issue #22: off, the folders lead the grid; on, a folder takes its place among the apps by name. */
    @Test fun foldersSitAmongTheAppsByNameWhenMixingIsOn() {
        runBlocking {
            wiring.settingsRepository.createDrawerFolder("Tools")
            wiring.settingsRepository.createDrawerFolder("games")
        }
        show()
        awaitState("both folders are listed") { it.folders.size == 2 }
        awaitText(mailApp)
        assertThat(orderOf("Tools")).isLessThan(orderOf("games"))
        assertThat(orderOf("games")).isLessThan(orderOf(mailApp))

        runBlocking { wiring.settingsRepository.setMixDrawerFolders(true) }
        awaitState("the setting reached the drawer") { it.mixDrawerFolders }
        settle()
        assertThat(orderOf(mailApp)).isLessThan(orderOf("games"))
        assertThat(orderOf("games")).isLessThan(orderOf("Tools"))
    }

    /** The tile labelled [text] in the grid's reading order: row first (its top), then column (its left). */
    private fun orderOf(text: String): Float =
        compose.onNodeWithText(text).getBoundsInRoot().let { it.top.value * 10_000f + it.left.value }

    private fun topOf(text: String): Float = compose.onNodeWithText(text).getBoundsInRoot().top.value

    /** A Telegram request: the drawer's folder card has as many columns as the folder columns setting. */
    @Test fun theFolderCardFollowsTheFolderColumnsSetting() {
        val members = listOf(mailApp, mapsApp, notesApp, docsApp)
        val id = runBlocking { wiring.settingsRepository.createDrawerFolder("Tools") }
        show()
        awaitState("the apps are listed") { s -> members.all { p -> s.apps.any { it.packageName == p } } }
        val keys = viewModel.uiState.value.apps.filter { it.packageName in members }.map { it.key }
        runBlocking { wiring.settingsRepository.addAppsToDrawerFolder(id, keys) }
        awaitState("the folder holds the four") { it.folders.firstOrNull()?.apps?.size == 4 }
        settle()
        compose.onNodeWithText("Tools").performClick()
        settle()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
        // Four members A-Z: com.docs, com.mail, com.maps, com.notes fill one row of four.
        assertThat(topOf(notesApp)).isEqualTo(topOf(mailApp))

        runBlocking { wiring.settingsRepository.setFolderColumns(3) }
        awaitState("the setting reached the drawer") { it.folderColumns == 3 }
        settle()
        assertThat(topOf(notesApp)).isGreaterThan(topOf(mailApp))
    }

    /** A Telegram request after 1.0.4: the folder card follows the folder appearance settings (app names off). */
    @Test fun theFolderCardFollowsTheFolderAppearanceSettings() {
        val members = listOf(mailApp, mapsApp)
        val id = runBlocking { wiring.settingsRepository.createDrawerFolder("Tools") }
        show()
        awaitState("the apps are listed") { s -> members.all { p -> s.apps.any { it.packageName == p } } }
        val keys = viewModel.uiState.value.apps.filter { it.packageName in members }.map { it.key }
        runBlocking { wiring.settingsRepository.addAppsToDrawerFolder(id, keys) }
        awaitState("the folder holds the two") { it.folders.firstOrNull()?.apps?.size == 2 }
        settle()
        compose.onNodeWithText("Tools").performClick()
        settle()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
        compose.onNodeWithText(mailApp).assertExists()

        runBlocking { wiring.settingsRepository.setFolderShowLabels(false) }
        awaitState("the setting reached the drawer") { !it.folderStyle.showLabels }
        settle()

        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
        compose.onNodeWithText(mailApp).assertDoesNotExist()
    }

    /** Issue #31: an app on a folder's card has the long-press menu an app in the grid has, and the card stays
     *  under the menu (the menu changes nothing in the folder). */
    @Test fun aLongPressOnAnAppOfTheFolderCardOpensItsMenu() {
        val id = runBlocking { wiring.settingsRepository.createDrawerFolder("Tools") }
        show()
        awaitState("the app is listed") { s -> s.apps.any { it.packageName == mailApp } }
        val keys = viewModel.uiState.value.apps.filter { it.packageName == mailApp }.map { it.key }
        runBlocking { wiring.settingsRepository.addAppsToDrawerFolder(id, keys) }
        awaitState("the folder holds it") { it.folders.firstOrNull()?.apps?.size == 1 }
        settle()
        compose.onNodeWithText("Tools").performClick()
        settle()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
        compose.onNodeWithText(appInfo()).assertDoesNotExist()

        compose.onNodeWithText(mailApp).performTouchInput { longClick() }
        awaitText(appInfo())

        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
    }

    /** The menu of a locked desktop: the rows that change nothing on it. What puts an app on the home screen or
     *  the dock, renames it or hides it is not offered (the lock used to be asked only in the grid, and "Add to
     *  home" on a card's menu went through). */
    private fun assertTheLockedMenu() {
        awaitText(appInfo())
        compose.onNodeWithText(context.getString(R.string.uninstall)).assertExists()
        listOf(R.string.add_to_home, R.string.add_to_dock, R.string.rename, R.string.hide_app).forEach {
            compose.onNodeWithText(context.getString(it)).assertDoesNotExist()
        }
    }

    /** Issue #30 (a follow-up): the lock keeps the home screen as it is, not the menu shut. A hold on an app of
     *  the grid opens the menu's short form and lifts nothing, so a pull afterwards drags nothing out. */
    @Test fun onALockedDesktopAHoldOnAnAppOfTheGridOpensTheShortMenuAndLiftsNothing() {
        runBlocking { wiring.settingsRepository.setDesktopLocked(true) }
        show()
        awaitState("the app is listed, on a locked desktop") { s -> s.desktopLocked && s.apps.any { it.packageName == mailApp } }
        settle()

        compose.onNodeWithText(mailApp).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        settle()

        assertTheLockedMenu()
        assertThat(drag.isDragging).isFalse()
        compose.onNodeWithText(mailApp).performTouchInput { repeat(8) { moveBy(Offset(0f, 40f)) } }
        settle()
        assertThat(drag.moving).isFalse()
        assertThat(dragOuts).isEqualTo(0)
        compose.onNodeWithText(mailApp).performTouchInput { up() }
        settle()
        compose.onNodeWithText(appInfo()).assertExists()
    }

    /** The same on a folder's card: the short menu, and the card stays under it (the release is no tap). */
    @Test fun onALockedDesktopAHoldOnAnAppOfTheFolderCardOpensTheShortMenu() {
        runBlocking { wiring.settingsRepository.setDesktopLocked(true) }
        showWithFilledFolder()
        awaitState("the lock reached the drawer") { it.desktopLocked }
        settle()
        compose.onNodeWithText("Tools").performClick()
        settle()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()

        compose.onNodeWithText(mailApp).performTouchInput { longClick() }

        assertTheLockedMenu()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()
    }

    /** A tap on the card's app still launches it on a locked desktop (the card closes with the launch). */
    @Test fun onALockedDesktopATapOnAnAppOfTheFolderCardStillLaunchesIt() {
        runBlocking { wiring.settingsRepository.setDesktopLocked(true) }
        showWithFilledFolder()
        awaitState("the lock reached the drawer") { it.desktopLocked }
        settle()
        compose.onNodeWithText("Tools").performClick()
        settle()
        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertExists()

        compose.onNodeWithText(mailApp).performClick()
        settle()

        compose.onNodeWithTag(DRAWER_FOLDER_CARD_TAG).assertDoesNotExist()
    }

    // --- Issue #33: the search field at the bottom -----------------------------------------------------

    /** The drawer with the "most used" card showing the mail app; returns once it is laid out. */
    private fun showWithAMostUsedApp(searchAtBottom: Boolean) {
        runBlocking {
            wiring.settingsRepository.setShowFrequentApps(true)
            wiring.settingsRepository.setDrawerSearchAtBottom(searchAtBottom)
        }
        show()
        awaitState("the app is listed") { s -> s.apps.any { it.packageName == mailApp } }
        val mail = viewModel.uiState.value.apps.first { it.packageName == mailApp }
        viewModel.onAppClick(mail) // a launch is what puts an app on the card
        awaitState("the card has the app") { s -> s.searchAtBottom == searchAtBottom && s.frequentApps.any { it.key == mail.key } }
        settle()
    }

    private fun boundsOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** As it always was: the field leads the drawer and the "most used" card leads the grid under it. */
    @Test fun byDefaultTheSearchFieldLeadsTheDrawerWithTheMostUsedAppsUnderIt() {
        showWithAMostUsedApp(searchAtBottom = false)

        val field = boundsOf(DRAWER_SEARCH_FIELD_TAG)
        val card = boundsOf(DRAWER_FREQUENT_TAG)
        assertThat(field.bottom).isAtMost(card.top)
    }

    /**
     * Issue #33: on a tall phone the field at the top is out of the thumb's reach. Asked for, it sits at the
     * bottom of the drawer, under the apps, and the "most used" card sits right above it instead of leading the
     * grid: both in reach, and the apps' own grid above them.
     */
    @Test fun askedForTheSearchFieldSitsAtTheBottomWithTheMostUsedAppsRightAboveIt() {
        showWithAMostUsedApp(searchAtBottom = true)

        val field = boundsOf(DRAWER_SEARCH_FIELD_TAG)
        val card = boundsOf(DRAWER_FREQUENT_TAG)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertThat(card.bottom).isAtMost(field.top)
        assertThat(field.top).isGreaterThan(root.height / 2)
        // The card is no longer the grid's first item: the apps' own tiles are above it.
        val tiles = compose.onAllNodesWithText(mailApp).fetchSemanticsNodes().map { it.boundsInRoot.top }
        assertThat(tiles.min()).isLessThan(card.top)
    }

    /** A search covers the drawer with its results: the "most used" card gives its place to them. */
    @Test fun whileSearchingTheMostUsedCardAboveTheBottomFieldIsGone() {
        showWithAMostUsedApp(searchAtBottom = true)

        viewModel.onQueryChange("mail")
        awaitState("the search") { it.query == "mail" }
        settle()

        compose.onNodeWithTag(DRAWER_FREQUENT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DRAWER_SEARCH_FIELD_TAG).assertExists()
    }

    /** The same on the "most used" card, the drawer's third way to an app's menu: it never asked the lock (older
     *  than the folder card's menu), and "Add to home" went through from there too. */
    @Test fun onALockedDesktopAHoldOnAMostUsedAppOpensTheShortMenu() {
        runBlocking {
            wiring.settingsRepository.setDesktopLocked(true)
            wiring.settingsRepository.setShowFrequentApps(true)
        }
        show()
        awaitState("the app is listed") { s -> s.apps.any { it.packageName == mailApp } }
        val mail = viewModel.uiState.value.apps.first { it.packageName == mailApp }
        viewModel.onAppClick(mail) // a launch is what puts an app on the card
        awaitState("the card has the app, on a locked desktop") { s -> s.desktopLocked && s.frequentApps.any { it.key == mail.key } }
        settle()

        // The card leads the grid: its tile is the first of the two "com.mail" on screen.
        compose.onAllNodesWithText(mailApp).onFirst().performTouchInput { longClick() }

        assertTheLockedMenu()
    }

    /** The drawer's folder "Tools" holding Mail and Maps, on screen. */
    private fun showWithFilledFolder() {
        val id = runBlocking { wiring.settingsRepository.createDrawerFolder("Tools") }
        show()
        val members = listOf(mailApp, mapsApp)
        awaitState("the apps are listed") { s -> members.all { p -> s.apps.any { it.packageName == p } } }
        val keys = viewModel.uiState.value.apps.filter { it.packageName in members }.map { it.key }
        runBlocking { wiring.settingsRepository.addAppsToDrawerFolder(id, keys) }
        awaitState("the folder holds the two") { it.folders.firstOrNull()?.apps?.size == 2 }
        settle()
    }

    /** A finger down on the folder's tile until the hold completes; it stays down. */
    private fun holdTheFolder() {
        compose.onNodeWithText("Tools").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        settle()
    }

    /** A Telegram request after 1.2.0: a folder of the drawer can be dragged to the home screen. A hold lifts it,
     *  as it lifts an app; a pull takes it out of the drawer, and that is not the editor's gesture. */
    @Test fun aHeldFolderIsLiftedAndAPullDragsItOutOfTheDrawer() {
        showWithFilledFolder()

        holdTheFolder()
        assertThat(drag.draggedDrawerFolder?.name).isEqualTo("Tools")
        assertThat(drag.draggedDrawerFolder?.apps?.map { it.packageName }).containsExactly(mailApp, mapsApp)
        assertThat(drag.moving).isFalse()
        assertThat(dragOuts).isEqualTo(0)

        compose.onNodeWithText("Tools").performTouchInput { repeat(8) { moveBy(Offset(0f, 40f)) } }
        settle()
        assertThat(drag.moving).isTrue()
        assertThat(dragOuts).isEqualTo(1)
        compose.onNodeWithText(addApps()).assertDoesNotExist()

        // Released over nothing (no home grid in this test): nothing is made, and no editor opens.
        compose.onNodeWithText("Tools").performTouchInput { up() }
        settle()
        assertThat(drag.isDragging).isFalse()
        compose.onNodeWithText(addApps()).assertDoesNotExist()
        assertThat(runBlocking { wiring.homeLayoutRepository.homeItems.first() }).isEmpty()
    }

    /** Dropped on the home grid, a copy of the folder is made there; the drawer keeps its own. */
    @Test fun aFolderDroppedOnTheHomeScreenIsCopiedThereAndStaysInTheDrawer() {
        showWithFilledFolder()
        // The home grid as the home screen publishes it to the controller: here the whole window, four by five.
        compose.runOnIdle {
            drag.gridBounds = Rect(0f, 0f, 4000f, 8000f)
            drag.columns = 4
            drag.rows = 5
        }

        holdTheFolder()
        compose.onNodeWithText("Tools").performTouchInput {
            repeat(8) { moveBy(Offset(0f, 40f)) }
            up()
        }
        settle()

        val deadline = System.currentTimeMillis() + 10_000
        while (runBlocking { wiring.homeLayoutRepository.homeItems.first() }.count { !it.isFolder } < 2) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the folder's copy on home" }
            Thread.sleep(20)
            // The drop's work may still have a step waiting on the main thread: a wait that only sleeps never
            // lets it run (one run in about ten timed out here).
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
        val rows = runBlocking { wiring.homeLayoutRepository.homeItems.first() }
        val folder = rows.single { it.isFolder }
        assertThat(folder.folderName).isEqualTo("Tools")
        assertThat(rows.filter { it.containerId == folder.id }.map { it.packageName }).containsExactly(mailApp, mapsApp)
        assertThat(viewModel.uiState.value.folders.map { it.name }).containsExactly("Tools")
        compose.onNodeWithText(addApps()).assertDoesNotExist()
    }

    /** Released where it was lifted, the hold is what it always was: the editor. */
    @Test fun aHeldFolderReleasedWhereItWasLiftedOpensItsEditor() {
        showWithFilledFolder()

        holdTheFolder()
        compose.onNodeWithText(addApps()).assertDoesNotExist() // not before the finger lifts: it may still pull
        compose.onNodeWithText("Tools").performTouchInput { up() }
        settle()

        compose.onNodeWithText(addApps()).assertExists()
        assertThat(drag.isDragging).isFalse()
        assertThat(dragOuts).isEqualTo(0)
    }

    /** A folder with no apps has nothing to copy: it is not lifted, and the hold opens the editor at once. */
    @Test fun anEmptyFolderIsNotLifted() {
        showWithFolder()

        holdTheFolder()

        assertThat(drag.isDragging).isFalse()
        compose.onNodeWithText(addApps()).assertExists()
        // The tile is the first "Tools" on screen; the editor's name field is the other.
        compose.onAllNodesWithText("Tools").onFirst().performTouchInput { up() }
    }

    /** A locked desktop takes nothing new: the folder is not lifted; its editor (the drawer's own) still opens. */
    @Test fun onALockedDesktopAFolderIsNotLifted() {
        runBlocking { wiring.settingsRepository.setDesktopLocked(true) }
        showWithFilledFolder()
        awaitState("the lock reached the drawer") { it.desktopLocked }
        settle()

        holdTheFolder()

        assertThat(drag.isDragging).isFalse()
        compose.onNodeWithText(addApps()).assertExists()
        // The tile is the first "Tools" on screen; the editor's name field is the other.
        compose.onAllNodesWithText("Tools").onFirst().performTouchInput { up() }
    }

    /** Mixed among the apps the order is by name, so the editor offers no moves. */
    @Test fun theEditorHidesMovesWhileFoldersMixWithTheApps() {
        runBlocking { wiring.settingsRepository.setMixDrawerFolders(true) }
        showWithFolder()
        awaitState("the setting reached the drawer") { it.mixDrawerFolders }
        compose.onNodeWithText("Tools").performTouchInput { longClick() }
        settle()

        compose.onNodeWithText(addApps()).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.folder_move_later)).assertDoesNotExist()
    }

    /** Issue #21: the drawer's surface takes the background opacity setting; solid by default. */
    @Test fun theDrawerSurfaceFollowsTheBackgroundOpacitySetting() {
        show()
        awaitState("the default opacity") { it.backgroundOpacity == 1f }

        runBlocking { wiring.settingsRepository.setDrawerBackgroundOpacity(0.6f) }
        awaitState("the chosen opacity") { it.backgroundOpacity == 0.6f }
    }

    /** A Telegram request after 0.9.9: the drawer's surface takes the background colour setting; the theme's by default,
     *  and the theme's again once the colour is cleared. */
    @Test fun theDrawerSurfaceFollowsTheBackgroundColourSetting() {
        show()
        awaitState("the theme's colour") { it.backgroundColor == null }

        runBlocking { wiring.settingsRepository.setDrawerBackgroundColor(0xFF42A5F5.toInt()) }
        awaitState("the chosen colour") { it.backgroundColor == 0xFF42A5F5.toInt() }

        runBlocking { wiring.settingsRepository.setDrawerBackgroundColor(null) }
        awaitState("the theme's colour again") { it.backgroundColor == null }
    }

    /** The owner's 1.0.1 ask: the drawer's labels take the chosen colour; automatic (black or white by what the drawer
     *  looks like) by default, and automatic again once the colour is cleared. */
    @Test fun theDrawerLabelsFollowTheLabelColourSetting() {
        show()
        awaitState("automatic") { it.labelColor == null }

        runBlocking { wiring.settingsRepository.setDrawerLabelColor(0xFF42A5F5.toInt()) }
        awaitState("the chosen colour") { it.labelColor == 0xFF42A5F5.toInt() }

        runBlocking { wiring.settingsRepository.setDrawerLabelColor(null) }
        awaitState("automatic again") { it.labelColor == null }
    }

    /**
     * Review round 10. The store of the drawer pictures follows a profile switch a moment late: it reads the new
     * profile's picture from the disk first. The drawer joined the new profile's folders and colours with
     * whatever picture the store showed, so an open drawer drew the previous profile's own picture for that
     * moment. The picture is taken only while it is the picture of the profile the state is of. (Here the store
     * the drawer reads never follows the switch at all, which holds that moment still.)
     */
    @Test fun theDrawerDoesNotShowAnotherProfilesPicture() {
        val dark = createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        runBlocking { wiring.drawerWallpaper.set(dark, 1) }
        show()
        awaitState("the first profile's picture") { it.homeProfileId == 1L && it.wallpaper != null }
        compose.onNodeWithTag(DRAWER_WALLPAPER_TAG).assertExists()
        val work = runBlocking {
            (wiring.homeProfiles.create("Work", copyOf = null) as HomeProfileRepository.Created.Done).profile.id
        }

        runBlocking { assertThat(wiring.homeProfiles.switchTo(work)).isTrue() }
        awaitState("Work is on") { it.homeProfileId == work }
        settle()

        assertThat(wiring.drawerWallpaper.shown.value.profile).isEqualTo(1L)
        assertThat(viewModel.uiState.value.wallpaper).isNull()
        compose.onNodeWithTag(DRAWER_WALLPAPER_TAG).assertDoesNotExist()
        // Back on the first profile, its picture is its own again.
        runBlocking { assertThat(wiring.homeProfiles.switchTo(1)).isTrue() }
        awaitState("the first profile's picture again") { it.homeProfileId == 1L && it.wallpaper != null }
    }

    /** A Telegram request after 0.9.9: the user's own picture reaches the drawer with its lightness, and is drawn under
     *  the surface; removed, the drawer is back to the system wallpaper. */
    @Test fun theDrawerShowsTheUsersOwnPictureWhileOneIsSet() {
        show()
        awaitState("no picture") { it.wallpaper == null }
        compose.onNodeWithTag(DRAWER_WALLPAPER_TAG).assertDoesNotExist()

        val dark = createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        runBlocking { wiring.drawerWallpaper.set(dark, 1) }
        awaitState("the picture") { it.wallpaper != null && it.wallpaper!!.luminance < 0.05f }
        compose.onNodeWithTag(DRAWER_WALLPAPER_TAG).assertExists()

        runBlocking { wiring.drawerWallpaper.clear(1) }
        awaitState("no picture again") { it.wallpaper == null }
        compose.onNodeWithTag(DRAWER_WALLPAPER_TAG).assertDoesNotExist()
    }

    /** The owner's 1.0.0 note: the dim over the user's own picture reaches the drawer; none out of the box. */
    @Test fun theDrawerCarriesThePictureDimSetting() {
        show()
        awaitState("no dim") { it.pictureDim == 0f }

        runBlocking { wiring.settingsRepository.setDrawerPictureDim(0.3f) }
        awaitState("the chosen dim") { it.pictureDim == 0.3f }
    }

    /** A Telegram request after 0.9.7: with "Hide apps that are on the home screen" on, an app placed on home
     *  leaves the drawer's list; the search still finds it. Off (the default) it stays listed. */
    @Test fun appsOnTheHomeScreenLeaveTheDrawerWhenTheSettingIsOn() {
        show()
        awaitText("com.mail")
        val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0L, "Mail")
        runBlocking { wiring.homeLayoutRepository.addToHome(mail, columns = 4, rows = 6) }
        awaitState("home knows the app") { mail.key in it.homeKeys }
        compose.onNodeWithText("com.mail").assertExists() // the default: still listed

        runBlocking { wiring.settingsRepository.setHideHomeAppsFromDrawer(true) }
        awaitState("the app left the list") { it.apps.none { a -> a.key == mail.key } }
        settle()
        compose.onAllNodesWithText("com.mail").assertCountEquals(0)

        viewModel.onQueryChange("mail")
        awaitText("com.mail") // the search still finds it
    }

    /** A dock that is off shows nothing, so its apps are not "on the home screen" and stay listed. */
    @Test fun aDockAppReturnsToTheDrawerWhenTheDockIsOff() {
        show()
        awaitText("com.mail")
        val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0L, "Mail")
        runBlocking {
            wiring.homeLayoutRepository.addToDock(mail)
            wiring.settingsRepository.setHideHomeAppsFromDrawer(true)
        }
        awaitState("the dock app left the list") { it.apps.none { a -> a.key == mail.key } }

        runBlocking { wiring.settingsRepository.setDockEnabled(false) }
        awaitState("the dock is off, the app is back") { it.apps.any { a -> a.key == mail.key } }
    }

    /** A Telegram request after 0.9.7: the drawer's icon size is its own setting; until set it follows home's. */
    @Test fun theDrawerIconScaleFollowsItsOwnSetting() {
        show()
        awaitState("the default scale") { it.iconScale == 1f }

        runBlocking { wiring.settingsRepository.setIconScale(1.2f) }
        awaitState("home's scale while the drawer's is unset") { it.iconScale == 1.2f }
        runBlocking { wiring.settingsRepository.setDrawerIconScale(0.9f) }
        awaitState("the drawer's own scale") { it.iconScale == 0.9f }
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
        // Only the visible text goes: TalkBack still gets the name from the tile.
        compose.onNodeWithContentDescription("Tools").assertExists()

        runBlocking { wiring.settingsRepository.setShowDrawerFolderNames(true) }
        awaitText("Tools")
        compose.onAllNodesWithContentDescription("Tools").assertCountEquals(0)
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
        assertThat(compose.onAllNodesWithTag(ADD_APPS_ROW_TAG).fetchSemanticsNodes())
            .hasSize(viewModel.uiState.value.folderCandidates.size)

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

    /** The drawer's own windows are opaque: over a dark picture the see-through drawer's labels turn white, but the
     *  Edit dialog, a light surface in this light theme, keeps dark text. */
    @Test fun theEditDialogKeepsItsOwnTextOverADarkPicture() {
        showWithApp()
        val dark = createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        runBlocking { wiring.drawerWallpaper.set(dark, 1) }
        awaitState("the dark picture") { it.wallpaper != null && it.wallpaper!!.luminance < 0.05f }
        settle()
        assertThat(textColorOf(mailApp).luminance()).isGreaterThan(0.5f)

        holdIcon()
        compose.onNodeWithText(context.getString(R.string.rename)).performClick()
        settle()

        assertThat(textColorOf(editTitle()).luminance()).isLessThan(0.5f)
    }

    /** The search's section titles sit on the see-through background like the app labels, so over a dark picture they
     *  turn white with them (the expressive palette, which the opaque cards keep, would leave them dark on dark). */
    @Test fun theSearchSectionTitlesFollowTheDrawersTextOverADarkPicture() {
        showWithApp()
        val dark = createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        runBlocking { wiring.drawerWallpaper.set(dark, 1) }
        awaitState("the dark picture") { it.wallpaper != null && it.wallpaper!!.luminance < 0.05f }
        settle()

        compose.onNode(hasSetTextAction()).performTextInput(mailApp.take(3))
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)) // the debounce elapses
        val appsTitle = context.getString(R.string.search_section_apps)
        awaitText(appsTitle, unmerged = true)

        assertThat(textColorOf(appsTitle).luminance()).isGreaterThan(0.5f)
    }

    /** A Telegram request after 1.2.0: the search field is as see-through as the drawer, so its text is picked by
     *  what shows through it, as the labels' is. Over the user's own dark picture a light theme's field used to be
     *  its own opaque light pill with dark text; see-through there, dark text would sit on the dark picture. */
    @Test fun theSearchFieldsTextFollowsWhatShowsThroughIt() {
        show()
        val hint = context.getString(R.string.app_drawer_search_hint)
        awaitText(hint, unmerged = true)
        // The solid drawer: the field's own pill decides, dark text on the light theme's.
        assertThat(textColorOf(hint).luminance()).isLessThan(0.5f)

        val dark = createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        runBlocking { wiring.drawerWallpaper.set(dark, 1) }
        awaitState("the dark picture") { it.wallpaper != null && it.wallpaper!!.luminance < 0.05f }
        settle()

        assertThat(textColorOf(hint).luminance()).isGreaterThan(0.5f)
    }

    /** The colour a text is laid out with (its Text's colour merged into its style). */
    private fun textColorOf(text: String): Color {
        val node = compose.onAllNodesWithText(text, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().layoutInput.style.color
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
