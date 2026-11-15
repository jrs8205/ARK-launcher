package org.arkikeskus.launcher.data

import android.os.Process
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.model.AppItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A home folder filled from a list and put in order (discussion #16). Several apps join at once, after the
 * apps the folder has: one that already sits on the home screen moves in, as if it had been dropped on the
 * folder, and one the folder already holds is not added twice. Sorting writes the order it is given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderListAndSortTest {
    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private lateinit var repo: HomeLayoutRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao()
        repo = HomeLayoutRepository(db, dao, FakeAppRowResolver())
    }

    @After
    fun tearDown() = db.close()

    private val a = app("a")
    private val b = app("b")
    private val c = app("c")
    private val d = app("d")
    private val e = app("e")

    private fun app(name: String) = AppItem("com.$name", "Main", Process.myUserHandle(), 0L, name)

    private suspend fun folderWith(vararg apps: AppItem): Long {
        val id = dao.insert(HomeItemEntity(folderName = "Tools", page = 0, cellX = 0, cellY = 0))
        apps.forEachIndexed { i, app ->
            dao.insert(HomeItemEntity(containerId = id, packageName = app.packageName, className = app.className, page = 0, cellX = i, cellY = 0))
        }
        return id
    }

    private suspend fun order(folderId: Long) = dao.getContainerOrdered(folderId).map { it.packageName }

    private suspend fun assertIndexed(folderId: Long) {
        dao.getContainerOrdered(folderId).forEachIndexed { i, row ->
            assertThat(row.page).isEqualTo(0)
            assertThat(row.cellY).isEqualTo(0)
            assertThat(row.cellX).isEqualTo(i)
        }
    }

    // --- Several apps at once -----------------------------------------------------------------------

    @Test
    fun `the picked apps join after the apps the folder has, in the order they were picked`() = runTest {
        val folder = folderWith(a, b)

        repo.addAppsToFolder(folder, listOf(d, c, e))

        assertThat(order(folder)).containsExactly("com.a", "com.b", "com.d", "com.c", "com.e").inOrder()
        assertIndexed(folder)
    }

    @Test
    fun `an app the folder already holds is not added twice`() = runTest {
        val folder = folderWith(a, b)

        repo.addAppsToFolder(folder, listOf(b, c, c))

        assertThat(order(folder)).containsExactly("com.a", "com.b", "com.c").inOrder()
    }

    @Test
    fun `an app that sits on the home screen moves into the folder`() = runTest {
        val folder = folderWith(a, b)
        dao.insert(HomeItemEntity(packageName = c.packageName, className = c.className, page = 0, cellX = 2, cellY = 1))

        repo.addAppsToFolder(folder, listOf(c))

        assertThat(order(folder)).containsExactly("com.a", "com.b", "com.c").inOrder()
        // Its own icon is the one that went in: no second icon of the app stays behind.
        assertThat(dao.getByKey(HOME, c.packageName, c.className, c.userSerial)).isNull()
    }

    @Test
    fun `an app in the dock stays in the dock and joins the folder too`() = runTest {
        val folder = folderWith(a, b)
        dao.insert(HomeItemEntity(containerId = DOCK, packageName = c.packageName, className = c.className, page = 0, cellX = 0, cellY = 0))

        repo.addAppsToFolder(folder, listOf(c))

        assertThat(order(folder)).contains("com.c")
        assertThat(dao.getByKey(DOCK, c.packageName, c.className, c.userSerial)).isNotNull()
    }

    @Test
    fun `nothing is added to a folder that is gone, or to a row that is no folder`() = runTest {
        val folder = folderWith(a, b)
        val plainApp = dao.insert(HomeItemEntity(packageName = c.packageName, className = c.className, page = 0, cellX = 3, cellY = 3))
        val before = dao.getAllOnce()

        repo.addAppsToFolder(folder + 100, listOf(d))
        repo.addAppsToFolder(plainApp, listOf(d))

        assertThat(dao.getAllOnce()).isEqualTo(before)
    }

    // --- The order -------------------------------------------------------------------------------------

    @Test
    fun `sorting writes the order it is given`() = runTest {
        val folder = folderWith(c, a, d, b)

        repo.sortFolder(folder, listOf(a.key, b.key, c.key, d.key))

        assertThat(order(folder)).containsExactly("com.a", "com.b", "com.c", "com.d").inOrder()
        assertIndexed(folder)
    }

    @Test
    fun `an app the order does not name keeps its place after the named ones`() = runTest {
        // A child that does not resolve right now (a disabled app, a paused profile) has no name to sort by.
        val folder = folderWith(c, e, a, d)

        repo.sortFolder(folder, listOf(a.key, c.key))

        assertThat(order(folder)).containsExactly("com.a", "com.c", "com.e", "com.d").inOrder()
        assertIndexed(folder)
    }

    @Test
    fun `sorting a folder that is gone changes nothing`() = runTest {
        val folder = folderWith(b, a)
        val before = dao.getAllOnce()

        repo.sortFolder(folder + 100, listOf(a.key, b.key))

        assertThat(dao.getAllOnce()).isEqualTo(before)
    }
}
