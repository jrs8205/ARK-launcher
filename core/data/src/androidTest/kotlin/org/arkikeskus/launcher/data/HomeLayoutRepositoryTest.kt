package org.arkikeskus.launcher.data

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.model.AppItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the home layout against a real in-memory Room database — exercises the
 * actual SQL, the unique (page, cellX, cellY) index, and the transactional swap.
 */
@RunWith(AndroidJUnit4::class)
class HomeLayoutRepositoryTest {

    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private lateinit var repo: HomeLayoutRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LauncherDatabase::class.java).build()
        dao = db.homeItemDao()
        repo = HomeLayoutRepository(db, dao)
    }

    @After
    fun tearDown() = db.close()

    private fun app(pkg: String) = AppItem(
        packageName = pkg,
        className = "$pkg.Main",
        user = Process.myUserHandle(),
        userSerial = 0L,
        label = pkg,
    )

    private fun HomeItemEntity.cell() = Triple(page, cellX, cellY)

    @Test
    fun moveItem_toFreeCell_moves() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6) // lands at (0,0,0)

        val accepted = repo.moveItem(app("a"), page = 0, cellX = 2, cellY = 1)

        assertThat(accepted).isTrue()
        val items = dao.getContainer(HomeItemEntity.HOME)
        assertThat(items).hasSize(1)
        assertThat(items.single().cell()).isEqualTo(Triple(0, 2, 1))
    }

    @Test
    fun moveItem_ontoOccupiedCell_swaps() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6) // (0,0,0)
        repo.addToHome(app("b"), columns = 4, rows = 6) // (0,1,0)

        val accepted = repo.moveItem(app("a"), page = 0, cellX = 1, cellY = 0)

        assertThat(accepted).isTrue()
        val byPkg = dao.getContainer(HomeItemEntity.HOME).associateBy { it.packageName }
        assertThat(byPkg.getValue("a").cell()).isEqualTo(Triple(0, 1, 0))
        assertThat(byPkg.getValue("b").cell()).isEqualTo(Triple(0, 0, 0))
        assertThat(dao.getContainer(HomeItemEntity.HOME).map { it.cell() }).containsNoDuplicates()
    }

    @Test
    fun moveItem_ontoOwnCell_isNoOp() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6) // (0,0,0)

        val accepted = repo.moveItem(app("a"), page = 0, cellX = 0, cellY = 0)

        assertThat(accepted).isTrue()
        assertThat(dao.getContainer(HomeItemEntity.HOME).single().cell()).isEqualTo(Triple(0, 0, 0))
    }

    @Test
    fun moveItem_missingApp_returnsFalse() = runTest {
        val accepted = repo.moveItem(app("ghost"), page = 0, cellX = 0, cellY = 0)

        assertThat(accepted).isFalse()
        assertThat(dao.getContainer(HomeItemEntity.HOME)).isEmpty()
    }

    @Test
    fun reflow_pullsOutOfRangeCellsBackOnScreen() = runTest {
        // As if previously placed in a 7-column grid.
        dao.insert(HomeItemEntity(packageName = "a", className = "a.M", userSerial = 0, page = 0, cellX = 0, cellY = 0))
        dao.insert(HomeItemEntity(packageName = "b", className = "b.M", userSerial = 0, page = 0, cellX = 6, cellY = 0))

        repo.reflow(columns = 4, rows = 6)

        val items = dao.getContainer(HomeItemEntity.HOME)
        assertThat(items.all { it.cellX in 0..3 }).isTrue()
        assertThat(items.map { it.cell() }).containsNoDuplicates()
    }

    @Test
    fun reflow_overflowsToNextPage() = runTest {
        // 13 items into a 2-wide grid → 12 slots per page, so one spills to page 1.
        repeat(13) { i ->
            dao.insert(
                HomeItemEntity(
                    packageName = "p$i",
                    className = "p$i.M",
                    userSerial = 0,
                    page = 0,
                    cellX = i % 4,
                    cellY = i / 4,
                ),
            )
        }

        repo.reflow(columns = 2, rows = 6)

        val items = dao.getContainer(HomeItemEntity.HOME)
        assertThat(items).hasSize(13)
        assertThat(items.all { it.cellX in 0..1 }).isTrue()
        assertThat(items.any { it.page == 1 }).isTrue()
        assertThat(items.map { it.cell() }).containsNoDuplicates()
    }

    @Test
    fun placeAt_newApp_freeCell_inserts() = runTest {
        val accepted = repo.placeAt(app("a"), page = 0, cellX = 2, cellY = 1, columns = 4, rows = 6)

        assertThat(accepted).isTrue()
        assertThat(dao.getContainer(HomeItemEntity.HOME).single().cell()).isEqualTo(Triple(0, 2, 1))
    }

    @Test
    fun placeAt_newApp_occupiedCell_fallsBackToFirstFree() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6) // occupies (0,0,0)

        val accepted = repo.placeAt(app("b"), page = 0, cellX = 0, cellY = 0, columns = 4, rows = 6)

        assertThat(accepted).isTrue()
        val byPkg = dao.getContainer(HomeItemEntity.HOME).associateBy { it.packageName }
        assertThat(byPkg.getValue("a").cell()).isEqualTo(Triple(0, 0, 0)) // untouched
        assertThat(byPkg.getValue("b").cell()).isEqualTo(Triple(0, 1, 0)) // first free cell
        assertThat(dao.getContainer(HomeItemEntity.HOME).map { it.cell() }).containsNoDuplicates()
    }

    @Test
    fun placeAt_existingApp_swaps() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6) // (0,0,0)
        repo.addToHome(app("b"), columns = 4, rows = 6) // (0,1,0)

        val accepted = repo.placeAt(app("a"), page = 0, cellX = 1, cellY = 0, columns = 4, rows = 6)

        assertThat(accepted).isTrue()
        val byPkg = dao.getContainer(HomeItemEntity.HOME).associateBy { it.packageName }
        assertThat(byPkg.getValue("a").cell()).isEqualTo(Triple(0, 1, 0))
        assertThat(byPkg.getValue("b").cell()).isEqualTo(Triple(0, 0, 0))
        assertThat(dao.getContainer(HomeItemEntity.HOME)).hasSize(2)
    }

    @Test
    fun addToHome_isIdempotentPerKey() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6)
        repo.addToHome(app("a"), columns = 4, rows = 6)

        assertThat(dao.getContainer(HomeItemEntity.HOME)).hasSize(1)
    }

    @Test
    fun createFolder_movesBothAppsIntoFolderAtTargetCell() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6) // (0,0,0)
        repo.addToHome(app("b"), columns = 4, rows = 6) // (0,1,0)
        val targetRow = dao.getByKey(HomeItemEntity.HOME, "a", "a.Main", 0L)!!

        val folderId = repo.createFolder(target = app("a"), dropped = app("b"), name = "Folder")

        assertThat(folderId).isGreaterThan(0L)
        val home = dao.getContainer(HomeItemEntity.HOME)
        assertThat(home).hasSize(1) // just the folder
        val folder = home.single()
        assertThat(folder.isFolder).isTrue()
        assertThat(folder.cell()).isEqualTo(targetRow.cell()) // folder took the target's cell
        val children = dao.getContainer(folderId).map { it.packageName }.toSet()
        assertThat(children).containsExactly("a", "b")
    }

    @Test
    fun addToFolder_appendsChild() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6)
        repo.addToHome(app("b"), columns = 4, rows = 6)
        repo.addToHome(app("c"), columns = 4, rows = 6)
        val folderId = repo.createFolder(app("a"), app("b"), "F")

        repo.addToFolder(app("c"), folderId)

        assertThat(dao.getContainer(folderId).map { it.packageName }).containsExactly("a", "b", "c")
        assertThat(dao.getContainer(HomeItemEntity.HOME)).hasSize(1) // only the folder remains on home
    }

    @Test
    fun removeFromFolder_dissolvesWhenOneRemains() = runTest {
        repo.addToHome(app("a"), columns = 4, rows = 6)
        repo.addToHome(app("b"), columns = 4, rows = 6)
        val folderId = repo.createFolder(app("a"), app("b"), "F")
        val folderCell = dao.getById(folderId)!!.cell()

        repo.removeFromFolder(app("a"), folderId, columns = 4, rows = 6)

        // Folder gone; the last app ("b") sits at the folder's old cell; "a" back on home elsewhere.
        assertThat(dao.getById(folderId)).isNull()
        val home = dao.getContainer(HomeItemEntity.HOME)
        assertThat(home.none { it.isFolder }).isTrue()
        val b = home.first { it.packageName == "b" }
        assertThat(b.cell()).isEqualTo(folderCell)
        assertThat(home.any { it.packageName == "a" }).isTrue()
    }
}
