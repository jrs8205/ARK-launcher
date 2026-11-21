package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A switch of the home profile as the home screen's state sees it, with the launcher's real stores: the
 * settings, the layout rows and the folders' icons each learn of the switch at their own moment, and no
 * state handed to the screen may be made of two profiles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeProfileSwitchTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val user = Process.myUserHandle()

    private fun app(pkg: String, label: String) = AppItem(pkg, "$pkg.Main", user, 0L, label)

    private val mail = app("com.mail", "Mail")
    private val maps = app("com.maps", "Maps")
    private val notes = app("com.notes", "Notes")
    private val calc = app("com.calc", "Calculator")

    private fun installAll() = listOf(mail, maps, notes, calc).forEach { installFakeApp(context, it.packageName, it.label) }

    private fun row(app: AppItem, cellX: Int, container: Long = HomeItemEntity.HOME) =
        HomeItemEntity(containerId = container, packageName = app.packageName, className = app.className, page = 0, cellX = cellX, cellY = 0)

    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    /** Every state the screen is handed from here on. */
    private fun TestScope.statesOf(viewModel: HomeViewModel): List<HomeUiState> {
        val seen = ArrayList<HomeUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { seen += it } }
        return seen
    }

    /** Profile 1: four columns, Mail, a folder with its own icon. Profile 2 ("Work"), left on: five columns, Maps,
     *  a folder with another icon on a second page, start page 1. Returns Work's id. */
    private suspend fun ProductionWiring.twoProfiles(): Long {
        settingsRepository.setHomeColumns(4)
        dao.insert(row(mail, cellX = 0))
        val homeFolder = dao.insert(HomeItemEntity(folderName = "Home things", page = 0, cellX = 1, cellY = 0))
        dao.insert(row(notes, cellX = 0, container = homeFolder))
        dao.insert(row(calc, cellX = 1, container = homeFolder))
        settingsRepository.setFolderIcon(FolderIconKey.home(homeFolder), CustomIcon.FromPack("com.pack", "home"))

        val work = (homeProfiles.create("Work", copyCurrent = false) as HomeProfileRepository.Created.Done).profile.id
        homeProfiles.switchTo(work)
        settingsRepository.settings.first { it.homeProfileId == work }
        settingsRepository.setHomeColumns(5)
        settingsRepository.setHomePages(home = 1, count = 2)
        dao.insert(row(maps, cellX = 0))
        val workFolder = dao.insert(HomeItemEntity(folderName = "Work things", page = 1, cellX = 1, cellY = 0))
        dao.insert(row(notes, cellX = 0, container = workFolder))
        dao.insert(row(calc, cellX = 1, container = workFolder))
        settingsRepository.setFolderIcon(FolderIconKey.home(workFolder), CustomIcon.FromPack("com.pack", "work"))
        return work
    }

    /** What a loaded state must be, by the profile it says it shows. */
    private fun assertOfOneProfile(state: HomeUiState, work: Long) {
        val ofWork = state.homeProfileId == work
        assertWithMessage("columns of the state of profile ${state.homeProfileId}")
            .that(state.settings.homeColumns).isEqualTo(if (ofWork) 5 else 4)
        assertWithMessage("the settings' own profile").that(state.settings.homeProfileId).isEqualTo(state.homeProfileId)
        assertWithMessage("apps of the state of profile ${state.homeProfileId}")
            .that(state.entries.filterIsInstance<PlacedApp>().map { it.app.packageName })
            .containsExactly(if (ofWork) "com.maps" else "com.mail")
        val folder = state.entries.filterIsInstance<PlacedFolder>().single()
        assertWithMessage("folder of the state of profile ${state.homeProfileId}")
            .that(folder.name to folder.icon)
            .isEqualTo(if (ofWork) "Work things" to CustomIcon.FromPack("com.pack", "work") else "Home things" to CustomIcon.FromPack("com.pack", "home"))
    }

    @Test fun noStateMixesTheLayoutOfOneProfileWithTheSettingsOfAnother() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val work = wiring.twoProfiles()
            val viewModel = wiring.homeViewModel()
            val seen = statesOf(viewModel)
            awaitUntil("the Work profile's state") { seen.last().loaded && seen.last().homeProfileId == work }

            for (target in listOf(1L, work, 1L, work, 1L, work)) {
                wiring.homeProfiles.switchTo(target)
                awaitUntil("the state of profile $target") {
                    seen.last().homeProfileId == target && seen.last().settings.homeProfileId == target
                }
            }

            val loaded = seen.filter { it.loaded }
            assertThat(loaded.map { it.homeProfileId }.distinct()).containsExactly(1L, work)
            loaded.forEach { assertOfOneProfile(it, work) }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun aSwitchLandsOnTheNewProfilesStartPage() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val work = wiring.twoProfiles()
            val viewModel = wiring.homeViewModel()
            val seen = statesOf(viewModel)
            awaitUntil("the Work profile's state") { seen.last().loaded && seen.last().homeProfileId == work }
            assertThat(seen.last().homePage).isEqualTo(1)
            assertThat(seen.last().pageCount).isEqualTo(2)

            wiring.homeProfiles.switchTo(1)
            awaitUntil("the first profile's state") { seen.last().homeProfileId == 1L }

            assertThat(seen.last().homePage).isEqualTo(0)
            assertThat(seen.last().pageCount).isEqualTo(1)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun theProfileThatIsOnIsKnownWhileNobodyWatchesTheScreen() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val work = wiring.twoProfiles()
            val viewModel = wiring.homeViewModel()
            awaitUntil("the profile that is on") { viewModel.activeHomeProfileId.value == work }

            // The home screen is behind Settings: nothing collects its state, which stays as it last was.
            wiring.homeProfiles.switchTo(1)

            awaitUntil("the switch to be known") { viewModel.activeHomeProfileId.value == 1L }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun aShortcutEventOfAnAppPinnedOnlyInAnotherProfileIsActedOn() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val work = wiring.twoProfiles()
            // Pinned in Work only; then the first profile is on.
            wiring.allowShortcutQueries()
            wiring.publishShortcut("route")
            val pinned = wiring.dao.insert(
                HomeItemEntity(packageName = context.packageName, shortcutId = "route", page = 0, cellX = 3, cellY = 3),
            )
            // A shortcut the app never had: the clean-up of a start removes its row, which tells when that
            // clean-up is over (it would remove the withdrawn shortcut's row as well, event or no event).
            val ghost = wiring.dao.insert(
                HomeItemEntity(packageName = context.packageName, shortcutId = "ghost", page = 0, cellX = 4, cellY = 3),
            )
            wiring.homeProfiles.switchTo(1)
            val viewModel = wiring.homeViewModel()
            val seen = statesOf(viewModel)
            awaitUntil("the first profile's state") { seen.last().loaded && seen.last().homeProfileId == 1L }
            awaitUntil("the clean-up of the start") { wiring.rowsOfProfile(work).none { it.id == ghost } }
            assertThat(wiring.rowsOfProfile(work).map { it.id }).contains(pinned)

            wiring.withdrawShortcut("route")

            awaitUntil("the row of the withdrawn shortcut to go") { wiring.rowsOfProfile(work).none { it.id == pinned } }
            // The row is gone before the clean-up has returned: let it run to its end, or the pin lock it
            // holds is never released and every later test that needs the lock waits for ever.
            val lockFree = launch(Dispatchers.Default) { ShortcutPinLock.withLock { } }
            awaitUntil("the pin lock to be free") { lockFree.isCompleted }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 3. The screen shows the first profile, whose second page is empty, and is asked to remove
     * it. The profile was switched a moment before (the screen has not heard yet): the other profile's second
     * page holds a folder of apps. "This page shows nothing, delete what is left on it" was true of the page
     * the screen showed and reached the other profile's page: its rows were deleted. A page operation is held
     * to the profile its screen shows; asked of another, it does nothing.
     */
    @Test fun aPageRemovedFromTheScreenOfAProfileThatIsNoLongerOnDeletesNothing() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val work = wiring.twoProfiles()
            // Work's second page also holds a plain app, which nothing stops a purge from deleting.
            wiring.dao.insert(row(notes, cellX = 3).copy(page = 1))
            // The first profile on screen, with an empty second page of its own.
            wiring.homeProfiles.switchTo(1)
            wiring.settingsRepository.settings.first { it.homeProfileId == 1L }
            wiring.settingsRepository.setHomePages(home = 0, count = 2)
            val viewModel = wiring.homeViewModel()
            val seen = statesOf(viewModel)
            awaitUntil("the first profile's two pages") { seen.last().loaded && seen.last().homeProfileId == 1L && seen.last().pageCount == 2 }
            assertThat(viewModel.isPageEmpty(1)).isTrue()
            val workRows = wiring.rowsOfProfile(work).map { it.id }

            // Switched behind the screen: its state is still the first profile's when it asks.
            wiring.homeProfiles.switchTo(work)
            assertThat(viewModel.uiState.value.homeProfileId).isEqualTo(1L)
            val result = viewModel.removeEmptyPage(1, profile = 1)

            assertThat(wiring.rowsOfProfile(work).map { it.id }).containsExactlyElementsIn(workRows)
            assertThat(wiring.rowsOfProfile(work).map { it.folderName }).contains("Work things")
            assertThat(result).isEqualTo(PAGE_NOT_SHOWN)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 4. A menu is opened on the screen of one profile; when its handler runs, the view model's state
     * can already be the other profile's (the screen has not been composed again yet). A page operation is for the
     * profile the MENU showed, which the screen hands over with the page: asked for a profile that is not on it
     * does nothing, though the view model's own state says another profile, the one that is on.
     */
    @Test fun aPageOperationIsForTheProfileItsMenuShowed() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val work = wiring.twoProfiles()
            // Work, on and on screen, with an empty third page a removal could take.
            wiring.settingsRepository.setHomePages(home = 1, count = 3)
            val viewModel = wiring.homeViewModel()
            val seen = statesOf(viewModel)
            awaitUntil("Work's three pages") { seen.last().loaded && seen.last().homeProfileId == work && seen.last().pageCount == 3 }
            assertThat(viewModel.isPageEmpty(2)).isTrue()

            // The menu of the first profile's screen, handled after the state turned to Work.
            assertThat(viewModel.removeEmptyPage(2, profile = 1)).isEqualTo(PAGE_NOT_SHOWN)
            assertThat(viewModel.insertPage(0, profile = 1)).isEqualTo(PAGE_NOT_SHOWN)
            val setHome = viewModel.setHomePage(0, profile = 1)
            awaitUntil("the home page's answer") { setHome.isCompleted }

            val now = wiring.settingsRepository.settings.first()
            assertThat(now.homeProfileId).isEqualTo(work)
            assertThat(now.homePageCount).isEqualTo(3)
            assertThat(now.homePage).isEqualTo(1)
            // The same, asked for the profile that is on, goes through.
            assertThat(viewModel.removeEmptyPage(2, profile = work)).isNotEqualTo(PAGE_NOT_SHOWN)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * What a profile's screen asks to be added goes to that profile, by that profile's grid, or nowhere: the grid
     * was read from the settings and the row stored in a transaction that read the profile again, so a switch
     * between the two stored the row in the other profile at a cell counted for the first one's columns.
     */
    @Test fun anAppAddedFromTheScreenOfAProfileThatIsNotOnIsNotAddedToTheOther() = runTest {
        installAll()
        installFakeApp(context, "com.cam", "Camera")
        val camera = app("com.cam", "Camera")
        val wiring = ProductionWiring(context, this)
        try {
            val work = wiring.twoProfiles()
            val viewModel = wiring.homeViewModel()
            val seen = statesOf(viewModel)
            awaitUntil("Work on screen") { seen.last().loaded && seen.last().homeProfileId == work }
            val workRows = wiring.rowsOfProfile(work).map { it.id }

            val refused = viewModel.addToHome(camera, profile = 1)
            awaitUntil("the first profile's answer") { refused.isCompleted }

            assertThat(wiring.rowsOfProfile(work).map { it.id }).containsExactlyElementsIn(workRows)
            assertThat(wiring.rowsOfProfile(1).map { it.packageName }).doesNotContain("com.cam")
            // Asked for the profile that is on, it is added.
            viewModel.addToHome(camera, profile = work)
            awaitUntil("the camera on Work's home") { wiring.rowsOfProfile(work).any { it.packageName == "com.cam" } }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * A shortcut pinned from a profile's screen takes that profile's hold BEFORE it waits for the pin lock, the
     * order every holder of both keeps. Seen from outside: while the pin waits for the lock, a switch waits too.
     */
    @Test fun aPinFromAScreenHoldsItsProfileBeforeItWaitsForThePinLock() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val work = wiring.twoProfiles()
            val viewModel = wiring.homeViewModel()
            val seen = statesOf(viewModel)
            awaitUntil("Work on screen") { seen.last().loaded && seen.last().homeProfileId == work }
            val item = org.arkikeskus.launcher.ui.AppShortcuts.Item("com.mail", "inbox", user, 0L, "Inbox", "Inbox", icon = null)

            var switched: Boolean? = true
            val pin = ShortcutPinLock.withLock {
                val pin = viewModel.pinShortcut(item, profile = work)
                // Time for the pin to come to the lock this test holds; it holds its profile meanwhile.
                repeat(100) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(5)
                }
                // On the wall clock, off the test's own: a switch that is free to happen takes real file writes.
                switched = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    kotlinx.coroutines.withTimeoutOrNull(2_000) { wiring.homeProfiles.switchTo(1) }
                }
                pin
            }
            awaitUntil("the pin's end") { pin.isCompleted }

            assertThat(switched).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }
}
