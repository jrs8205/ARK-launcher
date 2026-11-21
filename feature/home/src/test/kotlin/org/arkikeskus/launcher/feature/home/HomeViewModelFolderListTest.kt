package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A home folder filled from a list and sorted by name (discussion #16), through the view model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeViewModelFolderListTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val user = Process.myUserHandle()

    private fun app(pkg: String, label: String) = AppItem(pkg, "$pkg.Main", user, 0L, label)

    private val zeta = app("com.z", "zeta")
    private val mango = app("com.m", "Mango")
    private val apple = app("com.a", "apple")
    private val berry = app("com.b", "Berry")

    private fun installAll() = listOf(zeta, mango, apple, berry).forEach { installFakeApp(context, it.packageName, it.label) }

    private suspend fun ProductionWiring.folderOf(vararg apps: AppItem): Long {
        val folderId = dao.insert(HomeItemEntity(folderName = "Fruit", page = 0, cellX = 0, cellY = 0))
        apps.forEachIndexed { i, app ->
            dao.insert(HomeItemEntity(containerId = folderId, packageName = app.packageName, className = app.className, page = 0, cellX = i, cellY = 0))
        }
        return folderId
    }

    private suspend fun ProductionWiring.order(folderId: Long) = dao.getContainerOrdered(folderId).map { it.packageName }

    private suspend fun awaitUntil(what: String, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun thePickedAppsJoinTheFolder() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val folderId = wiring.folderOf(zeta, mango)
            val viewModel = wiring.homeViewModel()

            viewModel.addAppsToFolder(folderId, listOf(berry, apple), profile = 1)

            awaitUntil("the two apps to join") { wiring.order(folderId).size == 4 }
            assertThat(wiring.order(folderId)).containsExactly("com.z", "com.m", "com.b", "com.a").inOrder()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun sortingPutsTheFoldersAppsInTheOrderOfTheirNamesCaseAside() = runTest {
        installAll()
        val wiring = ProductionWiring(context, this)
        try {
            val folderId = wiring.folderOf(zeta, mango, berry, apple)
            val viewModel = wiring.homeViewModel()

            viewModel.sortFolderByName(folderId, profile = 1)

            // apple, Berry, Mango, zeta: by name as the app drawer lists apps, capitals aside.
            awaitUntil("the sorted order") { wiring.order(folderId).first() == "com.a" }
            assertThat(wiring.order(folderId)).containsExactly("com.a", "com.b", "com.m", "com.z").inOrder()
            wiring.clear(viewModel)
        } finally {
            wiring.close()
        }
    }

    @Test fun theAppsOfferedForAFolderAreTheOnesItDoesNotHoldByName() {
        val folder = PlacedFolder(id = 1, name = "Fruit", apps = listOf(mango, zeta), page = 0, cellX = 0, cellY = 0)

        val offered = folderCandidates(listOf(zeta, mango, berry, apple), folder)

        assertThat(offered).containsExactly(apple, berry).inOrder()
    }
}
