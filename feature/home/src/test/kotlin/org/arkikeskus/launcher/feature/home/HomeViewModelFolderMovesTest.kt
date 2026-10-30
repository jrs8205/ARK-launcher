package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.HomeNotice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the ViewModel does with an app dragged inside or out of an open folder: a reorder persists,
 * a drop on a full home reports [HomeNotice.HomeFull] and leaves the app in the folder, a drop on
 * the dock adds the dock entry BEFORE the child row goes (an interruption can only duplicate, never
 * lose), the remove pill deletes the child, and a folder left with one app dissolves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeViewModelFolderMovesTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private val user = Process.myUserHandle()
    private val a = AppItem("com.a", "Main", user, 0L, "A")
    private val b = AppItem("com.b", "Main", user, 0L, "B")

    /** A folder "Pair" at page 0 (0,0) holding A then B. */
    private suspend fun ProductionWiring.pairFolder(): Long {
        val folderId = dao.insert(HomeItemEntity(folderName = "Pair", page = 0, cellX = 0, cellY = 0))
        dao.insert(HomeItemEntity(containerId = folderId, packageName = "com.a", className = "Main", page = 0, cellX = 0, cellY = 0))
        dao.insert(HomeItemEntity(containerId = folderId, packageName = "com.b", className = "Main", page = 0, cellX = 1, cellY = 0))
        return folderId
    }

    private suspend fun ProductionWiring.homeRow(app: AppItem) = dao.getByKey(HOME, app.packageName, app.className, app.userSerial)

    @Test fun movingOutOfAFolderOntoAFullHomeTellsTheScreenAndKeepsTheApp() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val s = wiring.settingsRepository.settings.first()
            wiring.fillEveryShownPage(exceptPage = 0)
            val folderId = wiring.pairFolder()
            wiring.dao.insert(HomeItemEntity(page = 0, cellX = 1, cellY = 0, spanX = s.homeColumns - 1, spanY = 1, appWidgetId = 1, widgetProvider = "com.widgets/.Row"))
            wiring.dao.insert(HomeItemEntity(page = 0, cellX = 0, cellY = 1, spanX = s.homeColumns, spanY = s.homeRows - 1, appWidgetId = 2, widgetProvider = "com.widgets/.Rest"))
            val viewModel = wiring.homeViewModel()
            val notices = collectNotices(viewModel)

            viewModel.moveOutOfFolder(a, folderId, 0, 1, 1, "Folder")

            awaitUntil("the notice") { notices.isNotEmpty() }
            assertThat(notices).containsExactly(HomeNotice.HomeFull)
            assertThat(wiring.dao.getContainerOrdered(folderId).map { it.packageName }).containsExactly("com.a", "com.b").inOrder()
            assertThat(wiring.homeRow(a)).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun movingOutOfAFolderOntoAnEmptyCellPlacesItThere() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val folderId = wiring.pairFolder()
            val viewModel = wiring.homeViewModel()
            val notices = collectNotices(viewModel)

            viewModel.moveOutOfFolder(b, folderId, 0, 1, 1, "Folder")

            awaitUntil("the home row") { wiring.homeRow(b) != null }
            val row = wiring.homeRow(b)!!
            assertThat(row.cellX to row.cellY).isEqualTo(1 to 1)
            assertThat(notices).isEmpty()
            assertThat(wiring.dao.getById(folderId)).isNull() // dissolved: A took the folder's cell
            val rowA = wiring.homeRow(a)!!
            assertThat(rowA.cellX to rowA.cellY).isEqualTo(0 to 0)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun movingAFolderAppToTheDockMakesItsChildRowTheDockRow() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val folderId = wiring.pairFolder()
            val viewModel = wiring.homeViewModel()

            viewModel.moveFolderAppToDock(a, folderId)

            awaitUntil("the child to go") { wiring.dao.getByKey(folderId, a.packageName, a.className, a.userSerial) == null }
            assertThat(wiring.dao.getByKey(DOCK, a.packageName, a.className, a.userSerial)).isNotNull()
            assertThat(wiring.dao.getById(folderId)).isNull()
            val rowB = wiring.homeRow(b)!!
            assertThat(rowB.cellX to rowB.cellY).isEqualTo(0 to 0)
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun removingEntirelyDeletesTheChildAndDissolves() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val folderId = wiring.pairFolder()
            val viewModel = wiring.homeViewModel()

            viewModel.removeFromFolderEntirely(a, folderId)

            awaitUntil("the folder to dissolve") { wiring.dao.getById(folderId) == null }
            val rowB = wiring.homeRow(b)!!
            assertThat(rowB.cellX to rowB.cellY).isEqualTo(0 to 0)
            assertThat(wiring.dao.getByKey(DOCK, a.packageName, a.className, a.userSerial)).isNull()
            assertThat(wiring.homeRow(a)).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun reorderingInAFolderPersistsTheOrder() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val folderId = wiring.pairFolder()
            val viewModel = wiring.homeViewModel()

            viewModel.reorderInFolder(folderId, b, before = a)

            awaitUntil("the new order") { wiring.dao.getContainerOrdered(folderId).first().packageName == "com.b" }
            assertThat(wiring.dao.getContainerOrdered(folderId).map { it.cellX }).containsExactly(0, 1).inOrder()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /** Collects the notices as they arrive, on the thread that sends them. */
    /** Issue #20: the card's "Delete folder" removes a folder WITH its apps (the drag path's
     *  removeFolder keeps its empty-only guard). */
    @Test fun deletingAFolderRemovesItWithItsApps() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val folderId = wiring.pairFolder()
            val viewModel = wiring.homeViewModel()

            viewModel.deleteFolder(folderId)

            awaitUntil("the folder to go") { wiring.dao.getById(folderId) == null }
            assertThat(wiring.dao.getContainerOrdered(folderId)).isEmpty()
            assertThat(wiring.homeRow(a)).isNull()
            assertThat(wiring.homeRow(b)).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    private fun TestScope.collectNotices(viewModel: HomeViewModel): List<HomeNotice> {
        val notices = mutableListOf<HomeNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.toList(notices) }
        return notices
    }

    /** The ViewModel's work resumes on the main looper after each storage read, so idle it until [done]. */
    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
