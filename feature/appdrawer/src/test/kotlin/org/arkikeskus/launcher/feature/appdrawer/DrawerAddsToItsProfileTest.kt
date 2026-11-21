package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.ui.DockTarget
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the drawer adds to the home screen is added to the home profile the drawer's state was of when the user
 * asked, by that profile's grid, or not at all. "Add to home" read the grid from the settings and stored the row
 * in a transaction that read the profile again: with a switch between the two, the row went to the other profile
 * at a cell counted for the first one's columns (a fifth icon beside four full cells, never shown).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerAddsToItsProfileTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0L, "Mail")

    private suspend fun DrawerWiring.onHome() =
        homeLayoutRepository.homeItems.first().firstOrNull { it.containerId == HOME && it.packageName == mail.packageName }

    private suspend fun DrawerWiring.inDock() =
        homeLayoutRepository.homeItems.first().firstOrNull { it.containerId == DOCK && it.packageName == mail.packageName }

    /** A pause between two writes of the settings file (see where it is used). */
    private fun betweenWrites() = Thread.sleep(200)

    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    @Test fun anAppAddedForAProfileThatIsNotOnIsNotAdded() = runTest {
        val wiring = DrawerWiring(context, this)
        try {
            val viewModel = wiring.viewModel()

            // The first profile is on; the drawer's state was another profile's when the user asked.
            val asked = viewModel.addToHome(mail, profile = 2)
            awaitUntil("the answer") { asked.isCompleted }

            assertThat(wiring.onHome()).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * The app's menu is drawn from one profile's rows: "Remove from home" is offered because the app is on THAT
     * profile's home screen. With another profile on by the time it is tapped, the same app on the other
     * profile's home screen stays: the menu never offered to remove it from there.
     */
    @Test fun anAppRemovedFromHomeForAProfileThatIsNotOnStays() = runTest {
        val wiring = DrawerWiring(context, this)
        try {
            val viewModel = wiring.viewModel()
            viewModel.addToHome(mail, profile = 1)
            awaitUntil("the app on home") { wiring.onHome() != null }

            val asked = viewModel.removeFromHome(mail, profile = 2)
            awaitUntil("the answer") { asked.isCompleted }
            assertThat(wiring.onHome()).isNotNull()

            viewModel.removeFromHome(mail, profile = 1)
            awaitUntil("the app gone from home") { wiring.onHome() == null }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /** The same for the menu's two dock actions: each is asked for the profile the menu was drawn from. */
    @Test fun theDockIsChangedOnlyForTheProfileThatIsOn() = runTest {
        val wiring = DrawerWiring(context, this)
        try {
            val viewModel = wiring.viewModel()

            val added = viewModel.addToDock(mail, profile = 2)
            awaitUntil("the answer") { added.isCompleted }
            assertThat(wiring.inDock()).isNull()

            viewModel.addToDock(mail, profile = 1)
            awaitUntil("the app in the dock") { wiring.inDock() != null }

            val removed = viewModel.removeFromDock(mail, profile = 2)
            awaitUntil("the answer") { removed.isCompleted }
            assertThat(wiring.inDock()).isNotNull()

            viewModel.removeFromDock(mail, profile = 1)
            awaitUntil("the app gone from the dock") { wiring.inDock() == null }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 6. What is dropped from the drawer on the home screen or the dock is for the home profile
     * the drawer showed as the finger lifted. The cell or the tile under the drop was looked up first and the
     * row stored in a step that read the profile again: with a switch between the two the icon went to another
     * profile, at a cell counted for the first one's grid, or was folded with another profile's app. Asked for
     * a profile that is not on, nothing is placed.
     */
    @Test fun whatIsDroppedFromTheDrawerIsPlacedOnlyForTheProfileThatIsOn() = runTest {
        val wiring = DrawerWiring(context, this)
        try {
            val viewModel = wiring.viewModel()
            val maps = AppItem("com.maps", "com.maps.Main", Process.myUserHandle(), 0L, "Maps")
            suspend fun rows() = wiring.homeLayoutRepository.homeItems.first()

            val refused = listOf(
                viewModel.addToHomeAt(mail, page = 0, cellX = 1, cellY = 1, folderName = "Folder", profile = 2),
                viewModel.addFolderToHomeAt(DrawerFolderUi(3, "Tools", listOf(mail, maps)), page = 0, cellX = 2, cellY = 2, profile = 2),
                viewModel.dropOnDock(mail, DockTarget.Between(0), "Folder", profile = 2),
            )
            awaitUntil("the answers") { refused.all { it.isCompleted } }
            assertThat(rows()).isEmpty()

            viewModel.addToHomeAt(mail, page = 0, cellX = 1, cellY = 1, folderName = "Folder", profile = 1)
            viewModel.dropOnDock(mail, DockTarget.Between(0), "Folder", profile = 1)
            awaitUntil("the icon on home and in the dock") { wiring.onHome() != null && wiring.inDock() != null }
            assertThat(wiring.onHome()!!.let { it.cellX to it.cellY }).isEqualTo(1 to 1)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 6. A drawer folder's id is its profile's own: a profile made as a copy has folders with the
     * very same ids. What the folder's editor asks (sort, rename, add, remove, move, delete) is asked for the
     * profile the editor was drawn from, and changes nothing when another profile is on: the other profile's
     * folder of that id is not the one the user was looking at.
     */
    @Test fun aDrawerFoldersEditorChangesOnlyAFolderOfTheProfileThatIsOn() = runTest {
        installFakeApp(context, "com.notes", "Notes")
        installFakeApp(context, "com.mail", "Mail")
        val wiring = DrawerWiring(context, this)
        try {
            val settings = wiring.settingsRepository
            val notes = "com.notes/com.notes.Main/0"
            val mailKey = "com.mail/com.mail.Main/0"
            // The settings here are a real file, and each write renames a new file over it. On Windows that
            // rename fails now and then when it comes right on the heels of another write or of a first read
            // ("Unable to rename ... .tmp"): the writes of this test are spaced out, and the folder is stored
            // before the view model begins to read. The wiring reads the file itself as it starts (the app
            // repository follows the usage count from its first moment), so the first write waits a moment too.
            betweenWrites()
            val id = settings.createDrawerFolder("Tools")
            betweenWrites()
            settings.addAppsToDrawerFolder(id, listOf(notes, mailKey))
            betweenWrites()
            suspend fun folders() = settings.drawerFolders.first()
            val viewModel = wiring.viewModel()

            val refused = listOf(
                viewModel.sortDrawerFolderByName(id, profile = 2),
                viewModel.renameDrawerFolder(id, "Other", profile = 2),
                viewModel.removeAppFromDrawerFolder(id, mailKey, profile = 2),
                viewModel.addAppsToDrawerFolder(id, listOf("com.x/X/0"), profile = 2),
                viewModel.moveDrawerFolder(id, 1, profile = 2),
                viewModel.setDrawerFolderIcon(id, org.arkikeskus.launcher.model.CustomIcon.FromPack("com.pack", "tools"), profile = 2),
                viewModel.deleteDrawerFolder(id, profile = 2),
            )
            awaitUntil("the answers") { refused.all { it.isCompleted } }
            assertThat(folders()).containsExactly(org.arkikeskus.launcher.data.DrawerFolder(id, "Tools", listOf(notes, mailKey)))
            assertThat(settings.folderIcons.first()).isEmpty()

            // For the profile that is on: sorted by the apps' names, then renamed.
            viewModel.sortDrawerFolderByName(id, profile = 1)
            awaitUntil("the folder sorted") { folders().single().appKeys == listOf(mailKey, notes) }
            betweenWrites()
            viewModel.renameDrawerFolder(id, "Other", profile = 1)
            awaitUntil("the folder renamed") { folders().single().name == "Other" }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 8. The drawer's state named its home profile from the settings and the layout together, and
     * took the app drawer's folders and their icons from flows of their own that say no profile. Around a switch
     * one profile's folder showed for a moment under the other's id; two profiles can each have a folder of the
     * same id, so a folder opened in that moment was taken for the other profile's. Every state the screen is
     * handed is of one profile: its id, its folders and their icons.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun noStateShowsOneProfilesFolderOrItsIconUnderAnotherProfilesId() = runTest {
        val wiring = DrawerWiring(context, this)
        try {
            val settings = wiring.settingsRepository
            betweenWrites()
            val ofFirst = settings.createDrawerFolder("Of the first")
            betweenWrites()
            settings.setFolderIcon(FolderIconKey.drawer(ofFirst), CustomIcon.FromPack("com.pack", "first"))
            betweenWrites()
            val work = (wiring.homeProfiles.create("Work", copyOf = null) as HomeProfileRepository.Created.Done).profile.id
            betweenWrites()
            assertThat(wiring.homeProfiles.switchTo(work)).isTrue()
            settings.settings.first { it.homeProfileId == work }
            betweenWrites()
            // The same id in both profiles: each profile numbers its own folders.
            assertThat(settings.createDrawerFolder("Of Work")).isEqualTo(ofFirst)
            betweenWrites()
            settings.setFolderIcon(FolderIconKey.drawer(ofFirst), CustomIcon.FromPack("com.pack", "work"))
            betweenWrites()
            val viewModel = wiring.viewModel()
            val seen = java.util.Collections.synchronizedList(ArrayList<AppDrawerUiState>())
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { seen += it } }
            fun shows(profile: Long, name: String) = seen.lastOrNull()?.let { state ->
                state.homeProfileId == profile && state.folders.map { it.name } == listOf(name) && state.folders.single().icon != null
            } == true
            awaitUntil("Work's folder") { shows(work, "Of Work") }

            repeat(6) { round ->
                val to = if (round % 2 == 0) 1L else work
                betweenWrites()
                assertThat(wiring.homeProfiles.switchTo(to)).isTrue()
                awaitUntil("the folder of profile $to") { shows(to, if (to == 1L) "Of the first" else "Of Work") }
            }

            for (state in seen.toList()) {
                val folder = state.folders.singleOrNull() ?: continue
                val ofWork = state.homeProfileId == work
                assertWithMessage("the folder of a state of profile ${state.homeProfileId}")
                    .that(folder.name).isEqualTo(if (ofWork) "Of Work" else "Of the first")
                if (folder.icon != null) {
                    assertWithMessage("its icon").that(folder.icon)
                        .isEqualTo(CustomIcon.FromPack("com.pack", if (ofWork) "work" else "first"))
                }
            }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun anAppAddedForTheProfileThatIsOnIsAdded() = runTest {
        val wiring = DrawerWiring(context, this)
        try {
            val viewModel = wiring.viewModel()

            viewModel.addToHome(mail, profile = 1)

            awaitUntil("the app on home") { wiring.onHome() != null }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }
}
