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
}
