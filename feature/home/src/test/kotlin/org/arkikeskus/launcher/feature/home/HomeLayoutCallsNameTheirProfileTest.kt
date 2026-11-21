package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.WidgetPlacement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Review round 7, and the kind of finding every round before it had a few of: a change of the home layout
 * that acted on "the profile that is on" when its handler ran, not on the profile whose screen the user
 * touched. Every change the home screen can ask of the layout now names its profile, and is made only while
 * that profile is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeLayoutCallsNameTheirProfileTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val user = Process.myUserHandle()
    private fun app(name: String) = AppItem("com.$name", "Main", user, 0L, name)

    private val names = listOf("a", "b", "c", "d", "e", "g", "h", "i", "j")

    private fun installAll() = names.forEach { installFakeApp(context, "com.$it", it, className = "Main") }

    private fun row(name: String, container: Long, cellX: Int, cellY: Int = 0) =
        HomeItemEntity(containerId = container, packageName = "com.$name", className = "Main", page = 0, cellX = cellX, cellY = cellY)

    /** The rows a layout was seeded with, by what the asks below need to name. */
    private class Seeded(
        val folder: Long,
        val emptyFolder: Long,
        val dockFolder: Long,
        val widget: Long,
        val placeholder: Long,
        val shortcut: Long,
        val dock: List<Long>,
    )

    /**
     * One layout with something of every kind. Home: a, b, a folder of e, d, c (not in the order of their names),
     * a folder whose apps are not installed (it shows empty), a built-in widget, a widget waiting to be set up
     * again and a pinned shortcut. Dock: g, h and a folder of i and j.
     */
    private suspend fun ProductionWiring.seed(): Seeded {
        dao.clear()
        dao.insert(row("a", HOME, 0))
        dao.insert(row("b", HOME, 1))
        val folder = dao.insert(HomeItemEntity(folderName = "Things", page = 0, cellX = 2, cellY = 0))
        listOf("e", "d", "c").forEachIndexed { i, name -> dao.insert(row(name, folder, i)) }
        val emptyFolder = dao.insert(HomeItemEntity(folderName = "Gone", page = 0, cellX = 3, cellY = 0))
        listOf("gone1", "gone2").forEachIndexed { i, name -> dao.insert(row(name, emptyFolder, i)) }
        val widget = dao.insert(
            HomeItemEntity(page = 0, cellX = 0, cellY = 2, spanX = 2, spanY = 1, builtinType = HomeItemEntity.BUILTIN_SMARTSPACE),
        )
        val placeholder = dao.insert(HomeItemEntity(page = 0, cellX = 0, cellY = 3, spanX = 2, spanY = 1, widgetProvider = "com.widgets/.W"))
        val shortcut = dao.insert(HomeItemEntity(packageName = context.packageName, shortcutId = "s1", page = 0, cellX = 3, cellY = 3))
        val g = dao.insert(row("g", DOCK, 0))
        val h = dao.insert(row("h", DOCK, 1))
        val dockFolder = dao.insert(HomeItemEntity(containerId = DOCK, folderName = "Dock things", page = 0, cellX = 2, cellY = 0))
        listOf("i", "j").forEachIndexed { i, name -> dao.insert(row(name, dockFolder, i)) }
        return Seeded(folder, emptyFolder, dockFolder, widget, placeholder, shortcut, listOf(g, h, dockFolder))
    }

    /** Everything an ask can change: the rows, and the folders' own icons (a profile's own values). */
    private suspend fun ProductionWiring.layout(): Pair<List<HomeItemEntity>, Map<String, CustomIcon>> =
        dao.getAll().sortedBy { it.id } to settingsRepository.folderIcons.first()

    private class Ask(val name: String, val ask: suspend (HomeViewModel, Seeded, Long) -> Any?)

    /** Every change of the layout the home screen asks its view model for. */
    private val asks = listOf(
        Ask("moveItem") { vm, _, p -> vm.moveItem(app("a"), 0, 1, 4, p) },
        Ask("moveFolder") { vm, s, p -> vm.moveFolder(s.folder, 0, 2, 4, p) },
        Ask("createFolder") { vm, _, p -> vm.createFolder(app("a"), app("b"), "New", p) },
        Ask("addToFolder") { vm, s, p -> vm.addToFolder(app("a"), s.folder, p) },
        Ask("removeFolder") { vm, s, p -> vm.removeFolder(s.emptyFolder, p) },
        Ask("deleteFolder") { vm, s, p -> vm.deleteFolder(s.folder, p) },
        Ask("removeShortcut") { vm, s, p -> vm.removeShortcut(s.shortcut, p) },
        Ask("addWidgetAt") { vm, _, p -> vm.addWidgetAt(null, null, HomeItemEntity.BUILTIN_SMARTSPACE, WidgetPlacement(0, 2, 2, 2, 1), p) },
        Ask("removeWidget") { vm, s, p -> vm.removeWidget(s.widget, p) },
        Ask("bindRestoredWidget") { vm, s, p -> vm.bindRestoredWidget(s.placeholder, 77, p) },
        Ask("setWidgetBounds") { vm, s, p -> vm.setWidgetBounds(s.widget, 0, 2, 2, 2, 1, p) },
        Ask("reorderDock") { vm, s, p ->
            vm.reorderDock(listOf(DockApp(s.dock[1], app("h")), DockApp(s.dock[0], app("g"))), p)
        },
        Ask("addToDockAt") { vm, _, p -> vm.addToDockAt(app("b"), 0, p) },
        Ask("createDockFolder") { vm, _, p -> vm.createDockFolder(app("g"), app("a"), HOME, "New", p) },
        Ask("addToDockFolder") { vm, s, p -> vm.addToDockFolder(app("a"), s.dockFolder, HOME, p) },
        Ask("moveFolderToDock") { vm, s, p -> vm.moveFolderToDock(s.folder, 0, p) },
        Ask("moveDockFolderToHome") { vm, s, p -> vm.moveDockFolderToHome(s.dockFolder, 0, 1, 4, p) },
        Ask("moveToHome") { vm, _, p -> vm.moveToHome(app("g"), 0, 1, 4, "New", p) },
        Ask("moveToDock") { vm, _, p -> vm.moveToDock(app("a"), 0, p) },
        Ask("removeFromFolder") { vm, s, p -> vm.removeFromFolder(app("c"), s.folder, p) },
        Ask("addAppsToFolder") { vm, s, p -> vm.addAppsToFolder(s.folder, listOf(app("a")), p) },
        Ask("sortFolderByName") { vm, s, p -> vm.sortFolderByName(s.folder, p) },
        Ask("reorderInFolder") { vm, s, p -> vm.reorderInFolder(s.folder, app("c"), before = app("e"), profile = p) },
        Ask("moveOutOfFolder") { vm, s, p -> vm.moveOutOfFolder(app("c"), s.folder, 0, 1, 4, "New", p) },
        Ask("moveFolderAppToDock") { vm, s, p -> vm.moveFolderAppToDock(app("c"), s.folder, 0, p) },
        Ask("removeFromFolderEntirely") { vm, s, p -> vm.removeFromFolderEntirely(app("c"), s.folder, p) },
        Ask("renameFolder") { vm, s, p -> vm.renameFolder(s.folder, "Renamed", p) },
        Ask("setFolderIcon") { vm, s, p -> vm.setFolderIcon(s.folder, CustomIcon.FromPack("com.pack", "own"), p) },
        Ask("removeFromHome") { vm, _, p -> vm.removeFromHome(app("a"), p) },
        Ask("removeFromDock") { vm, _, p -> vm.removeFromDock(app("g"), p) },
        Ask("addToHome") { vm, _, p -> vm.addToHome(app("j"), p) },
    )

    /** Runs [ask] to its end: what a launch gave is waited for. */
    private suspend fun done(result: Any?) {
        if (result is Job) awaitUntil("the answer") { result.isCompleted }
    }

    @Test fun aChangeAskedForAProfileThatIsNotOnChangesNothingAndOneAskedForTheProfileThatIsOnIsMade() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val viewModel = wiring.homeViewModel()

            val reached = ArrayList<String>()
            val refused = ArrayList<String>()
            for (ask in asks) {
                val seeded = wiring.seed()
                // The view model's state shows the layout: one ask looks a folder up in it.
                awaitUntil("the state of ${ask.name}") {
                    val state = viewModel.uiState.first()
                    state.loaded && state.entries.any { it is PlacedFolder && it.id == seeded.emptyFolder }
                }
                val before = wiring.layout()

                done(ask.ask(viewModel, seeded, 2))
                if (wiring.layout() != before) reached += ask.name

                done(ask.ask(viewModel, seeded, 1))
                if (wiring.layout() == before) refused += ask.name
            }

            assertWithMessage("changed the layout though asked for a profile that is not on").that(reached).isEmpty()
            assertWithMessage("changed nothing though asked for the profile that is on").that(refused).isEmpty()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 7 (a home icon to the dock, a dock icon to the home screen). With a copy of the profile on,
     * the same apps are on both home screens and in both docks, so a move asked for the profile that left
     * would find its icon in the copy as well. Nothing of the copy moves.
     */
    @Test fun anIconDraggedBetweenHomeAndDockOnTheScreenOfAProfileThatLeftDoesNotMoveInItsCopy() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val viewModel = wiring.homeViewModel()
            wiring.seed()
            val copy = (wiring.homeProfiles.create("Copy", copyOf = 1) as HomeProfileRepository.Created.Done).profile.id
            assertThat(wiring.homeProfiles.switchTo(copy)).isTrue()
            wiring.settingsRepository.settings.first { it.homeProfileId == copy }
            awaitUntil("the copy on screen") { viewModel.uiState.first().let { it.loaded && it.homeProfileId == copy } }
            val before = wiring.rowsOfProfile(copy)
            assertThat(before.map { it.packageName }).containsAtLeast("com.a", "com.g")

            done(viewModel.moveToDock(app("a"), 0, profile = 1))
            done(viewModel.moveToHome(app("g"), 0, 1, 4, "New", profile = 1))
            assertThat(wiring.rowsOfProfile(copy)).containsExactlyElementsIn(before)

            // Asked for the copy itself, both are made.
            done(viewModel.moveToDock(app("a"), 0, profile = copy))
            done(viewModel.moveToHome(app("g"), 0, 1, 4, "New", profile = copy))
            val after = wiring.rowsOfProfile(copy)
            assertThat(after.single { it.packageName == "com.a" }.containerId).isEqualTo(DOCK)
            assertThat(after.single { it.packageName == "com.g" }.containerId).isEqualTo(HOME)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 7 (a widget's bind). A widget is bound and set up in the system's dialogs, and the profile can
     * be switched before they are done. The view model then stores nothing and says so, which is what makes the
     * screen free the widget id it had allocated; a placeholder's bind is refused the same way, and a widget's
     * row that was not removed does not have its id freed.
     */
    @Test fun aWidgetIsStoredBoundAndRemovedOnlyForTheProfileItsScreenShowed() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val viewModel = wiring.homeViewModel()
            val seeded = wiring.seed()
            val before = wiring.layout()

            assertThat(viewModel.addWidgetAt(31, "com.widgets/.W", null, WidgetPlacement(0, 2, 2, 2, 1), profile = 2)).isFalse()
            assertThat(viewModel.bindRestoredWidget(seeded.placeholder, 32, profile = 2)).isFalse()
            var freed = 0
            done(viewModel.removeWidget(seeded.widget, profile = 2) { freed++ })
            assertThat(wiring.layout()).isEqualTo(before)
            assertThat(freed).isEqualTo(0)

            assertThat(viewModel.addWidgetAt(31, "com.widgets/.W", null, WidgetPlacement(0, 2, 2, 2, 1), profile = 1)).isTrue()
            assertThat(viewModel.bindRestoredWidget(seeded.placeholder, 32, profile = 1)).isTrue()
            done(viewModel.removeWidget(seeded.widget, profile = 1) { freed++ })
            assertThat(wiring.dao.getAll().mapNotNull { it.appWidgetId }).containsExactly(31, 32)
            assertThat(wiring.dao.getAll().none { it.id == seeded.widget }).isTrue()
            assertThat(freed).isEqualTo(1)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /**
     * Review round 7 (an icon dragged to the remove area read the profile from the view model's state as it
     * was when the finger lifted). The screen is made for one profile and names THAT one in everything it
     * asks: each call of the view model in the screen's source that takes a profile is given the screen's own
     * (`profile`), or the one a widget's bind has carried through the system's dialogs (`bind.profile`), and
     * the screen never reads a profile from the view model's state to hand it back.
     */
    @Test fun theScreenHandsItsOwnProfileToEveryChangeItAsksFor() {
        val sources = File("src/main/kotlin/org/arkikeskus/launcher/feature/home")
        val viewModel = File(sources, "HomeViewModel.kt").readText()
        val screen = File(sources, "HomeScreen.kt").readText()
        // The view model's functions that are asked for a profile: `profile: Long` among their parameters.
        val forAProfile = Regex("""fun (\w+)\(([^)]*)\)""").findAll(viewModel)
            .filter { Regex("""\bprofile: Long\b""").containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1] }.toSet()
        assertThat(forAProfile).containsAtLeast("moveItem", "moveToDock", "addWidgetAt", "removeFromHome", "insertPage")

        val wrong = ArrayList<String>()
        for (call in Regex("""viewModel\.(\w+)\(""").findAll(screen)) {
            val name = call.groupValues[1]
            if (name !in forAProfile) continue
            val arguments = argumentsAt(screen, call.range.last)
            val last = arguments.substringAfterLast(',').trim()
            if (last != "profile" && last != "bind.profile") wrong += "$name($arguments)"
        }
        assertWithMessage("calls that do not name the screen's own profile").that(wrong).isEmpty()
        assertThat(screen).doesNotContain("uiState.value.homeProfileId")
    }

    /** The text between the parenthesis opened at [open] and the one that closes it. */
    private fun argumentsAt(source: String, open: Int): String {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("no closing parenthesis")
    }

    /** Lets the collectors run through their storage reads and main-looper hops. */
    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }
}
