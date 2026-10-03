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
    private val resolver = FakeAppRowResolver()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao()
        repo = HomeLayoutRepository(db, dao, resolver)
    }

    @After
    fun tearDown() = db.close()

    private fun app(pkg: String) = AppItem(pkg, "$pkg.Main", Process.myUserHandle(), 0L, pkg)

    private suspend fun homeApp(pkg: String, x: Int, y: Int = 0, id: Long = 0, cls: String = "$pkg.Main") = dao.insert(
        HomeItemEntity(id = id, packageName = pkg, className = cls, page = 0, cellX = x, cellY = y),
    )

    private suspend fun shortcut(pkg: String, id: String, x: Int, container: Long = HOME) = dao.insert(
        HomeItemEntity(containerId = container, packageName = pkg, shortcutId = id, page = 0, cellX = x, cellY = 0),
    )

    private fun launchable(vararg classes: String) = PackageTargets.Launchable(classes.toList())

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

    // --- Renamed launcher activities, vanished shortcuts, under-filled folders ------------------

    @Test
    fun sweep_followsALauncherActivityTheAppRenamed() = runTest {
        homeApp("a", x = 2, y = 1, cls = "a.Old")
        resolver.targets["a"] = launchable("a.New")

        repo.removeStaleAppRows { _, _ -> true }

        val row = dao.getContainer(HOME).single()
        assertThat(row.className).isEqualTo("a.New")
        assertThat(Triple(row.page, row.cellX, row.cellY)).isEqualTo(Triple(0, 2, 1))
    }

    @Test
    fun sweep_deletesARowWhoseActivityIsGoneOrAmbiguous() = runTest {
        homeApp("b", x = 0, cls = "b.Old")
        homeApp("c", x = 1, cls = "c.Old")
        homeApp("ok", x = 2, cls = "ok.Main")
        resolver.targets["b"] = launchable("b.One", "b.Two")
        resolver.targets["c"] = launchable()
        resolver.targets["ok"] = launchable("ok.Main", "ok.Other")

        repo.removeStaleAppRows { _, _ -> true }

        assertThat(homeKeys()).containsExactly("ok")
    }

    @Test
    fun sweep_dropsARenamedRowWhenTheNewActivityIsAlreadyOnHome() = runTest {
        homeApp("a", x = 0, cls = "a.Old", id = 1)
        homeApp("a", x = 1, cls = "a.New", id = 2)
        resolver.targets["a"] = launchable("a.New")

        repo.removeStaleAppRows { _, _ -> true }

        assertThat(dao.getContainer(HOME).map { it.id }).containsExactly(2L)
    }

    @Test
    fun sweep_leavesRowsAloneWhenThePackageStateIsUnknown() = runTest {
        // Unknown = a disabled app, a paused profile or a failed query: never remapped or deleted.
        homeApp("d", x = 0, cls = "d.Old")

        repo.removeStaleAppRows { _, _ -> true }

        assertThat(dao.getContainer(HOME).single().className).isEqualTo("d.Old")
    }

    @Test
    fun sweep_repairsFolderChildrenAndDissolvesTheFolder() = runTest {
        val f = dao.insert(HomeItemEntity(folderName = "F", page = 0, cellX = 3, cellY = 2))
        dao.insert(HomeItemEntity(containerId = f, packageName = "x", className = "x.Old", page = 0, cellX = 0, cellY = 0))
        dao.insert(HomeItemEntity(containerId = f, packageName = "y", className = "y.Old", page = 0, cellX = 1, cellY = 0))
        resolver.targets["x"] = launchable("x.New")
        resolver.targets["y"] = launchable()

        repo.removeStaleAppRows { _, _ -> true }

        assertThat(dao.getById(f)).isNull()
        val promoted = dao.getAt(HOME, 0, 3, 2)!!
        assertThat(promoted.key).isEqualTo("x/x.New/0")
    }

    @Test
    fun sweep_dissolvesFoldersAlreadyLeftWithOneOrNoApps() = runTest {
        val empty = folder(0)
        val single = folder(1, "a")

        repo.removeStaleAppRows { _, _ -> true }

        assertThat(dao.getById(empty)).isNull()
        assertThat(dao.getById(single)).isNull()
        assertThat(dao.getAt(HOME, 0, 1, 0)?.packageName).isEqualTo("a")
    }

    @Test
    fun sweep_deletesOnlyShortcutsTheSystemDefinitelyNoLongerHas() = runTest {
        shortcut("p", "gone", x = 0)
        shortcut("p", "kept", x = 1)
        shortcut("q", "unanswered", x = 2)
        resolver.missing["p"] = setOf("gone")

        repo.removeStaleAppRows { _, _ -> true }

        assertThat(dao.getContainer(HOME).map { it.shortcutId }).containsExactly("kept", "unanswered")
    }

    @Test
    fun sweep_deletesShortcutRowsInsideAFolder() = runTest {
        // Folders render only apps; a shortcut child (an old restore) is an invisible extra child.
        val f = folder(0, "a", "b")
        shortcut("p", "s", x = 2, container = f)

        repo.removeStaleAppRows { _, _ -> true }

        assertThat(dao.getContainerOrdered(f).map { it.packageName }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun sweep_keepsAppsThatAreOnlyHiddenForNow() = runTest {
        // An app on ejected storage reads as "not installed" until the card is back.
        homeApp("sd", x = 0)
        homeApp("gone", x = 1)
        resolver.unavailable += "sd"

        repo.removeStaleAppRows { pkg, _ -> pkg !in setOf("sd", "gone") }

        assertThat(homeKeys()).containsExactly("sd")
    }

    // --- Removing a folder outright --------------------------------------------------------------

    @Test
    fun removeFolder_deletesTheFolderAndEveryChild() = runTest {
        homeApp("a", x = 0)
        val f = folder(1, "a", "b", "c")
        val other = folder(2, "d", "e")

        repo.removeFolder(f)

        assertThat(dao.getById(f)).isNull()
        assertThat(dao.getAll().none { it.containerId == f }).isTrue()
        assertThat(homeKeys()).containsExactly("a")
        assertThat(dao.getContainerOrdered(other)).hasSize(2)
    }

    @Test
    fun removeFolder_isANoOpForAMissingFolderOrAnotherRow() = runTest {
        val app = homeApp("a", x = 0)

        repo.removeFolder(999L)
        repo.removeFolder(app)

        assertThat(homeKeys()).containsExactly("a")
    }
}
