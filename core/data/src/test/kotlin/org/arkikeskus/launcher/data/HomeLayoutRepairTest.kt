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
 * The home layout's self-repair paths (folder give-backs, the startup ghost sweep) against a real
 * in-memory Room database, so the unique cell index and the transactions are exercised too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeLayoutRepairTest {

    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private lateinit var repo: HomeLayoutRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao()
        repo = HomeLayoutRepository(db, dao)
    }

    @After
    fun tearDown() = db.close()

    private fun app(pkg: String) = AppItem(pkg, "$pkg.Main", Process.myUserHandle(), 0L, pkg)

    private suspend fun homeApp(pkg: String, x: Int, y: Int = 0, id: Long = 0) = dao.insert(
        HomeItemEntity(id = id, packageName = pkg, className = "$pkg.Main", page = 0, cellX = x, cellY = y),
    )

    private suspend fun folder(x: Int, vararg children: String): Long {
        val id = dao.insert(HomeItemEntity(folderName = "F", page = 0, cellX = x, cellY = 0))
        children.forEachIndexed { i, pkg ->
            dao.insert(HomeItemEntity(containerId = id, packageName = pkg, className = "$pkg.Main", page = 0, cellX = i, cellY = 0))
        }
        return id
    }

    private suspend fun homeKeys() = dao.getContainer(HOME).filter { it.isApp }.map { it.packageName }

    @Test
    fun removeFromFolder_mergesIntoTheIconAlreadyOnHome() = runTest {
        homeApp("a", x = 0)
        val f = folder(1, "a", "b", "c")

        repo.removeFromFolder(app("a"), f, columns = 4, rows = 6)

        assertThat(homeKeys()).containsExactly("a")
        assertThat(dao.getContainerOrdered(f).map { it.packageName }).containsExactly("b", "c").inOrder()
        assertThat(dao.getContainerOrdered(f).map { it.cellX }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun dissolve_dropsTheLastChildWhenItsAppIsAlreadyOnHome() = runTest {
        homeApp("a", x = 0)
        val f = folder(1, "a", "b")

        repo.removeFromFolder(app("b"), f, columns = 4, rows = 6)

        assertThat(dao.getById(f)).isNull()
        assertThat(homeKeys()).containsExactly("a", "b")
        assertThat(dao.getAt(HOME, 0, 1, 0)).isNull() // the folder's cell is simply empty now
        assertThat(dao.getAll().none { it.containerId == f }).isTrue()
    }

    @Test
    fun dissolve_stillPromotesTheLastChildOntoTheFolderCell() = runTest {
        val f = folder(2, "a", "b")

        repo.removeFromFolder(app("b"), f, columns = 4, rows = 6)

        assertThat(dao.getById(f)).isNull()
        assertThat(dao.getAt(HOME, 0, 2, 0)?.packageName).isEqualTo("a")
    }

    @Test
    fun sweep_deletesDuplicateRowsOfOneApp_keepingTheLowestId() = runTest {
        homeApp("a", x = 3, id = 7)
        homeApp("a", x = 1, id = 5)
        homeApp("b", x = 2, id = 6)
        val f = folder(0, "c", "c", "d")

        repo.removeStaleAppRows { _, _ -> true }

        val home = dao.getContainer(HOME).filter { it.isApp }
        assertThat(home.map { it.id }).containsExactly(5L, 6L)
        assertThat(dao.getContainerOrdered(f).map { it.packageName }).containsExactly("c", "d").inOrder()
        assertThat(dao.getContainerOrdered(f).map { it.cellX }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun sweep_keepsTheSameAppOnHomeAndInsideAFolder() = runTest {
        homeApp("a", x = 1)
        val f = folder(0, "a", "b")

        repo.removeStaleAppRows { _, _ -> true }

        assertThat(homeKeys()).containsExactly("a")
        assertThat(dao.getContainerOrdered(f).map { it.packageName }).containsExactly("a", "b")
    }
}
