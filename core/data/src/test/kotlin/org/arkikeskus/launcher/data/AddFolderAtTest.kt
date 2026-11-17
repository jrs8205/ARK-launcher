package org.arkikeskus.launcher.data

import android.os.Process
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
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
 * A folder of the app drawer dragged to the home screen (a Telegram request after 1.2.0): a new home folder with
 * the same name and apps is made at the cell, and nothing already on the home screen is moved or taken into it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddFolderAtTest {

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

    private fun app(name: String) = AppItem("com.$name", "Main", Process.myUserHandle(), 0L, name)
    private val mail = app("mail")
    private val maps = app("maps")
    private val notes = app("notes")

    private suspend fun insertOnHome(app: AppItem, page: Int, cellX: Int, cellY: Int) =
        dao.insert(HomeItemEntity(packageName = app.packageName, className = app.className, page = page, cellX = cellX, cellY = cellY))

    private suspend fun add(apps: List<AppItem>, cellX: Int, cellY: Int, page: Int = 0) =
        repo.addFolderAt("Tools", apps, page, cellX, cellY, columns = 2, rows = 2)

    private suspend fun childrenOf(folderId: Long) = dao.getContainerOrdered(folderId).map { it.packageName }

    @Test
    fun aFreeCellTakesTheFolderWithItsAppsInOrder() = runTest {
        val id = add(listOf(mail, maps, notes), cellX = 1, cellY = 1)

        val folder = dao.getAt(HOME, 0, 1, 1)!!
        assertThat(folder.id).isEqualTo(id)
        assertThat(folder.isFolder).isTrue()
        assertThat(folder.folderName).isEqualTo("Tools")
        assertThat(childrenOf(folder.id)).containsExactly("com.mail", "com.maps", "com.notes").inOrder()
    }

    @Test
    fun aTakenCellSendsTheFolderToTheFirstFreeCell() = runTest {
        insertOnHome(notes, 0, 0, 0)

        val id = add(listOf(mail, maps), cellX = 0, cellY = 0)

        val folder = dao.getById(id!!)!!
        assertThat(Triple(folder.page, folder.cellX, folder.cellY)).isEqualTo(Triple(0, 1, 0))
        // What was in the cell is still there, as an app of its own.
        assertThat(dao.getAt(HOME, 0, 0, 0)!!.packageName).isEqualTo("com.notes")
    }

    /** Dragging a folder out never takes an icon off the home screen: an app that has one keeps it, and the
     *  folder gets a row of its own for it. */
    @Test
    fun anAppAlreadyOnTheHomeScreenKeepsItsIcon() = runTest {
        insertOnHome(mail, 0, 0, 0)

        val id = add(listOf(mail, maps), cellX = 1, cellY = 1)

        assertThat(dao.getAt(HOME, 0, 0, 0)!!.packageName).isEqualTo("com.mail")
        assertThat(childrenOf(id!!)).containsExactly("com.mail", "com.maps").inOrder()
    }

    @Test
    fun anAppNamedTwiceIsInTheFolderOnce() = runTest {
        val id = add(listOf(mail, maps, mail), cellX = 0, cellY = 0)

        assertThat(childrenOf(id!!)).containsExactly("com.mail", "com.maps").inOrder()
    }

    @Test
    fun aFolderWithNoAppsIsNotMade() = runTest {
        assertThat(add(emptyList(), cellX = 0, cellY = 0)).isNull()
        assertThat(dao.getContainer(HOME)).isEmpty()
    }

    /** Every page the pager shows is full: nothing is stored, and the caller is told. */
    @Test
    fun aFullHomeScreenTakesNothing() = runTest {
        val pages = HomeLayoutRepository.MAX_PAGES
        var n = 0
        for (page in 0 until pages) for (x in 0..1) for (y in 0..1) insertOnHome(app("filler${n++}"), page, x, y)
        val before = dao.getContainer(HOME).size

        assertThat(add(listOf(mail, maps), cellX = 0, cellY = 0)).isNull()

        assertThat(dao.getContainer(HOME)).hasSize(before)
        assertThat(dao.getByKey(HOME, "com.mail", "Main", 0L)).isNull()
    }
}
