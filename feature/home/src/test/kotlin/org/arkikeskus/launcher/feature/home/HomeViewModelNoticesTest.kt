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
 * What the screen is told when a placement the user asked for finds every page the pager shows
 * full: the ViewModel reports it once through [HomeViewModel.notices] and leaves the layout — and
 * the dock, and the folder — as they were. With room left nothing is reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeViewModelNoticesTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private val app = AppItem("com.a", "Main", Process.myUserHandle(), 0L, "A")

    @Test fun addingAnAppToAFullHomeTellsTheScreen() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            wiring.fillEveryShownPage()
            val viewModel = wiring.homeViewModel()
            val notices = collectNotices(viewModel)

            viewModel.addToHome(app)

            awaitUntil("the notice") { notices.isNotEmpty() }
            assertThat(notices).containsExactly(HomeNotice.HomeFull)
            assertThat(wiring.dao.getByKey(HOME, app.packageName, app.className, app.userSerial)).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun addingAnAppWithRoomLeftSaysNothing() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            wiring.fillEveryShownPage(exceptPage = 5)
            val viewModel = wiring.homeViewModel()
            val notices = collectNotices(viewModel)

            viewModel.addToHome(app)

            awaitUntil("the row") { wiring.dao.getByKey(HOME, app.packageName, app.className, app.userSerial) != null }
            assertThat(wiring.dao.getByKey(HOME, app.packageName, app.className, app.userSerial)?.page).isEqualTo(5)
            assertThat(notices).isEmpty()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun takingAnAppOutOfAFolderOnAFullHomeKeepsItThereAndTellsTheScreen() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            val s = wiring.settingsRepository.settings.first()
            wiring.fillEveryShownPage(exceptPage = 0)
            // Page 0: the folder in its first cell, widgets over every other cell.
            val folderId = wiring.dao.insert(HomeItemEntity(folderName = "Pair", page = 0, cellX = 0, cellY = 0))
            wiring.dao.insert(HomeItemEntity(containerId = folderId, packageName = "com.a", className = "Main", page = 0, cellX = 0, cellY = 0))
            wiring.dao.insert(HomeItemEntity(containerId = folderId, packageName = "com.b", className = "Main", page = 0, cellX = 1, cellY = 0))
            wiring.dao.insert(HomeItemEntity(page = 0, cellX = 1, cellY = 0, spanX = s.homeColumns - 1, spanY = 1, appWidgetId = 1, widgetProvider = "com.widgets/.Row"))
            wiring.dao.insert(HomeItemEntity(page = 0, cellX = 0, cellY = 1, spanX = s.homeColumns, spanY = s.homeRows - 1, appWidgetId = 2, widgetProvider = "com.widgets/.Rest"))
            val viewModel = wiring.homeViewModel()
            val notices = collectNotices(viewModel)

            viewModel.removeFromFolder(app, folderId)

            awaitUntil("the notice") { notices.isNotEmpty() }
            assertThat(notices).containsExactly(HomeNotice.HomeFull)
            assertThat(wiring.dao.getContainer(folderId).map { it.packageName }).containsExactly("com.a", "com.b")
            assertThat(wiring.dao.getByKey(HOME, app.packageName, app.className, app.userSerial)).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun droppingADockAppOnAFullHomeKeepsItInTheDockAndTellsTheScreen() = runTest {
        val wiring = ProductionWiring(context, this)
        try {
            wiring.fillEveryShownPage()
            wiring.homeLayoutRepository.addToDock(app)
            val viewModel = wiring.homeViewModel()
            val notices = collectNotices(viewModel)

            viewModel.moveToHome(app, page = 3, cellX = 0, cellY = 0, folderName = "Folder")

            awaitUntil("the notice") { notices.isNotEmpty() }
            assertThat(notices).containsExactly(HomeNotice.HomeFull)
            assertThat(wiring.dao.getByKey(DOCK, app.packageName, app.className, app.userSerial)).isNotNull()
            assertThat(wiring.dao.getByKey(HOME, app.packageName, app.className, app.userSerial)).isNull()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    /** Collects the notices as they arrive, on the thread that sends them. */
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
