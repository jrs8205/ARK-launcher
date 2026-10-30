package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The dock the ViewModel shows comes from the DOCK rows: apps and folders in slot order, cut to the
 * dock's columns, an app that no longer resolves skipped. The favourites key list a version before
 * 0.9.7 left behind turns into rows once, at the first start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeViewModelDockTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val user = Process.myUserHandle()
    private fun app(name: String) = AppItem("com.$name", "Main", user, 0L, name)

    /** Lists the apps in the test device's LauncherApps; before the wiring, which queries them eagerly. */
    private fun installed(vararg names: String) {
        for (name in names) installFakeApp(context, "com.$name", name, className = "Main")
    }

    private suspend fun ProductionWiring.dockRow(app: AppItem, slot: Int): Long =
        dao.insert(HomeItemEntity(containerId = DOCK, packageName = app.packageName, className = app.className, page = 0, cellX = slot, cellY = 0))

    private suspend fun ProductionWiring.dockFolder(name: String, slot: Int, vararg apps: AppItem): Long {
        val id = dao.insert(HomeItemEntity(containerId = DOCK, folderName = name, page = 0, cellX = slot, cellY = 0))
        apps.forEachIndexed { i, app ->
            dao.insert(HomeItemEntity(containerId = id, packageName = app.packageName, className = app.className, page = 0, cellX = i, cellY = 0))
        }
        return id
    }

    @Test fun theDockListsItsRowsInSlotOrderCutToTheColumnsSkippingAppsThatDoNotResolve() = runTest {
        installed("a", "b", "c", "d", "e")
        val wiring = ProductionWiring(context, this)
        try {
            wiring.settingsRepository.setDockColumns(3)
            wiring.dockRow(app("a"), 0)
            wiring.dockRow(app("gone"), 1) // not installed: skipped, not counted against the columns
            val folder = wiring.dockFolder("Tools", 2, app("b"), app("c"))
            wiring.dockRow(app("d"), 3)
            wiring.dockRow(app("e"), 4) // beyond the three columns: a hidden tail
            val viewModel = wiring.homeViewModel()

            val entries = awaitState(viewModel) { it.loaded && it.dockEntries.size == 3 }.dockEntries

            assertThat(entries.map { it.rowId }).containsExactly(wiring.dao.getByKey(DOCK, "com.a", "Main", 0L)!!.id, folder, wiring.dao.getByKey(DOCK, "com.d", "Main", 0L)!!.id).inOrder()
            val dockFolder = entries[1] as DockFolder
            assertThat(dockFolder.folder.name).isEqualTo("Tools")
            assertThat(dockFolder.folder.apps.map { it.packageName }).containsExactly("com.b", "com.c").inOrder()
            assertThat(dockFolder.folder.cellX).isEqualTo(2)
            assertThat((entries[0] as DockApp).app.packageName).isEqualTo("com.a")
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun anOlderVersionsFavouritesKeyBecomesDockRowsAtTheFirstStart() = runTest {
        installed("a", "b")
        val wiring = ProductionWiring(context, this)
        try {
            wiring.settingsRepository.importRaw(mapOf("dock_favorites" to "com.a/Main/0\ncom.b/Main/0"))
            val viewModel = wiring.homeViewModel()

            awaitUntil("the rows") { wiring.dao.getContainerOrdered(DOCK).map { it.packageName } == listOf("com.a", "com.b") }
            awaitUntil("the key gone") { wiring.settingsRepository.legacyDockFavorites().isEmpty() }
            assertThat(wiring.dao.getContainerOrdered(DOCK).map { it.cellX }).containsExactly(0, 1).inOrder()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun reorderingTheDockWritesTheSlotsAndKeepsTheHiddenTail() = runTest {
        installed("a", "b", "c", "d")
        val wiring = ProductionWiring(context, this)
        try {
            wiring.settingsRepository.setDockColumns(3)
            val ra = wiring.dockRow(app("a"), 0)
            val rb = wiring.dockRow(app("b"), 1)
            val rc = wiring.dockRow(app("c"), 2)
            wiring.dockRow(app("d"), 3)
            val viewModel = wiring.homeViewModel()
            val shown = awaitState(viewModel) { it.loaded && it.dockEntries.size == 3 }.dockEntries

            viewModel.reorderDock(listOf(shown[2], shown[0], shown[1]))

            awaitUntil("the new order") { wiring.dao.getContainerOrdered(DOCK).map { it.id } == listOf(rc, ra, rb, wiring.dao.getByKey(DOCK, "com.d", "Main", 0L)!!.id) }
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    private suspend fun awaitState(viewModel: HomeViewModel, done: (HomeUiState) -> Boolean): HomeUiState {
        var state = viewModel.uiState.value
        awaitUntil("the state") { state = viewModel.uiState.first(); done(state) }
        return state
    }

    /** Lets the collectors run through their storage reads and main-looper hops. */
    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
