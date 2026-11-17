package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.os.Looper
import android.os.Process
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.arkikeskus.launcher.data.FolderIconKey
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the drawer's view model stores when one of its folders is dropped on the home screen: a copy of the
 * folder (the drawer keeps its own), with the icon picked for it when that icon is an icon pack's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerFolderToHomeTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val wiring = DrawerWiring(context, TestScope())
    private val viewModel = wiring.viewModel()

    @After fun tearDown() {
        wiring.clear(viewModel)
        wiring.close()
    }

    private fun app(name: String) = AppItem("com.$name", "Main", Process.myUserHandle(), 0L, name)
    private val mail = app("mail")
    private val maps = app("maps")

    private fun homeRows(): List<HomeItemEntity> = runBlocking { wiring.homeLayoutRepository.homeItems.first() }

    /** The view model's writes run on its own scope: waits, bounded, until the home rows say [done]. */
    private fun awaitHome(what: String, done: (List<HomeItemEntity>) -> Boolean): List<HomeItemEntity> {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val rows = homeRows()
            if (done(rows)) return rows
            check(System.currentTimeMillis() < deadline) { "Timed out waiting until $what" }
            pause()
        }
    }

    /** The view model works on the main thread between its reads and writes: lets it, and the stores, get on. */
    private fun pause() {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aFolderOfTwoAppsBecomesAHomeFolderAtTheCell() {
        viewModel.addFolderToHomeAt(DrawerFolderUi(3, "Tools", listOf(mail, maps)), page = 0, cellX = 2, cellY = 1)

        val rows = awaitHome("the folder is on home") { rows -> rows.any { it.isFolder } }
        val folder = rows.single { it.containerId == HOME }
        assertThat(folder.folderName).isEqualTo("Tools")
        assertThat(Triple(folder.page, folder.cellX, folder.cellY)).isEqualTo(Triple(0, 2, 1))
        assertThat(rows.filter { it.containerId == folder.id }.sortedBy { it.cellX }.map { it.packageName })
            .containsExactly("com.mail", "com.maps").inOrder()
    }

    /** A home folder holds two apps or more (one that is left with a single app turns into that app): a drawer
     *  folder of one app lands as the app's own icon. */
    @Test fun aFolderOfOneAppBecomesThatAppsIcon() {
        viewModel.addFolderToHomeAt(DrawerFolderUi(3, "Tools", listOf(mail)), page = 0, cellX = 1, cellY = 1)

        val rows = awaitHome("the app is on home") { it.isNotEmpty() }
        assertThat(rows).hasSize(1)
        assertThat(rows.single().isFolder).isFalse()
        assertThat(rows.single().packageName).isEqualTo("com.mail")
    }

    @Test fun anIconFromAPackGoesWithTheFolder() {
        val icon = CustomIcon.FromPack("com.pack", "ic_tools")
        viewModel.addFolderToHomeAt(DrawerFolderUi(3, "Tools", listOf(mail, maps), icon), page = 0, cellX = 0, cellY = 0)

        val folder = awaitHome("the folder is on home") { rows -> rows.any { it.isFolder } }.single { it.isFolder }
        val deadline = System.currentTimeMillis() + 10_000
        while (runBlocking { wiring.settingsRepository.folderIcons.first() }[FolderIconKey.home(folder.id)] == null) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the folder's icon" }
            pause()
        }
        assertThat(runBlocking { wiring.settingsRepository.folderIcons.first() }[FolderIconKey.home(folder.id)]).isEqualTo(icon)
    }

    /** A picture of the user's is one file with one owner: the copy on home does not share it (deleting either
     *  folder's icon would take the file from the other). It shows the preview of its apps instead. */
    @Test fun aPictureIconStaysWithTheDrawersFolder() {
        val picture = CustomIcon.FromFile("picture-1")
        viewModel.addFolderToHomeAt(DrawerFolderUi(3, "Tools", listOf(mail, maps), picture), page = 0, cellX = 0, cellY = 0)

        val folder = awaitHome("the folder is on home") { rows -> rows.any { it.isFolder } }.single { it.isFolder }
        repeat(10) { pause() } // nothing is to come: give a wrong write the time to land
        assertThat(runBlocking { wiring.settingsRepository.folderIcons.first() }[FolderIconKey.home(folder.id)]).isNull()
    }
}
