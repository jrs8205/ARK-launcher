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
        repo.addToHome(app("a"), columns = 4) // lands at (0,0,0)

        val accepted = repo.moveItem(app("a"), page = 0, cellX = 2, cellY = 1)

        assertThat(accepted).isTrue()
        val items = dao.getAll()
        assertThat(items).hasSize(1)
        assertThat(items.single().cell()).isEqualTo(Triple(0, 2, 1))
    }

    @Test
    fun moveItem_ontoOccupiedCell_swaps() = runTest {
        repo.addToHome(app("a"), columns = 4) // (0,0,0)
        repo.addToHome(app("b"), columns = 4) // (0,1,0)

        val accepted = repo.moveItem(app("a"), page = 0, cellX = 1, cellY = 0)

        assertThat(accepted).isTrue()
        val byPkg = dao.getAll().associateBy { it.packageName }
        assertThat(byPkg.getValue("a").cell()).isEqualTo(Triple(0, 1, 0))
        assertThat(byPkg.getValue("b").cell()).isEqualTo(Triple(0, 0, 0))
        assertThat(dao.getAll().map { it.cell() }).containsNoDuplicates()
    }

    @Test
    fun moveItem_ontoOwnCell_isNoOp() = runTest {
        repo.addToHome(app("a"), columns = 4) // (0,0,0)

        val accepted = repo.moveItem(app("a"), page = 0, cellX = 0, cellY = 0)

        assertThat(accepted).isTrue()
        assertThat(dao.getAll().single().cell()).isEqualTo(Triple(0, 0, 0))
    }

    @Test
    fun moveItem_missingApp_returnsFalse() = runTest {
        val accepted = repo.moveItem(app("ghost"), page = 0, cellX = 0, cellY = 0)

        assertThat(accepted).isFalse()
        assertThat(dao.getAll()).isEmpty()
    }

    @Test
    fun reflow_pullsOutOfRangeCellsBackOnScreen() = runTest {
        // As if previously placed in a 7-column grid.
        dao.insert(HomeItemEntity(packageName = "a", className = "a.M", userSerial = 0, page = 0, cellX = 0, cellY = 0))
        dao.insert(HomeItemEntity(packageName = "b", className = "b.M", userSerial = 0, page = 0, cellX = 6, cellY = 0))

        repo.reflow(columns = 4)

        val items = dao.getAll()
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

        repo.reflow(columns = 2)

        val items = dao.getAll()
        assertThat(items).hasSize(13)
        assertThat(items.all { it.cellX in 0..1 }).isTrue()
        assertThat(items.any { it.page == 1 }).isTrue()
        assertThat(items.map { it.cell() }).containsNoDuplicates()
    }

    @Test
    fun addToHome_isIdempotentPerKey() = runTest {
        repo.addToHome(app("a"), columns = 4)
        repo.addToHome(app("a"), columns = 4)

        assertThat(dao.getAll()).hasSize(1)
    }
}
