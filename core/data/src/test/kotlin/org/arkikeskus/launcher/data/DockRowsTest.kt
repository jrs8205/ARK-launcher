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
 * The dock as a `home_items` container (Launcher3's hotseat): rows in [DOCK] at page 0, cellY 0,
 * cellX = slot. Apps and folders live there like on home; the old newline-joined favourites key
 * migrates into rows once. Every operation keeps the slots numbered 0..n-1 in order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DockRowsTest {

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

    private fun app(name: String) = AppItem("com.$name", "Main", Process.myUserHandle(), 0L, name)

    private suspend fun dock() = dao.getContainerOrdered(DOCK)

    /** The dock in slot order as "a", "b", "[name]" for a folder. */
    private suspend fun dockOrder() = dock().map { if (it.isFolder) "[${it.folderName}]" else it.packageName.removePrefix("com.") }

    private suspend fun dockRow(app: AppItem) = dao.getByKey(DOCK, app.packageName, app.className, app.userSerial)

    private suspend fun homeRow(app: AppItem) = dao.getByKey(HOME, app.packageName, app.className, app.userSerial)

    private suspend fun childRow(folderId: Long, app: AppItem) =
        dao.getByKey(folderId, app.packageName, app.className, app.userSerial)

    private suspend fun insertOnHome(app: AppItem, page: Int, cellX: Int, cellY: Int) =
        dao.insert(HomeItemEntity(packageName = app.packageName, className = app.className, page = page, cellX = cellX, cellY = cellY))

    private suspend fun homeFolderWith(vararg apps: AppItem, at: Pair<Int, Int> = 0 to 0): Long {
        val id = dao.insert(HomeItemEntity(folderName = "Pair", page = 0, cellX = at.first, cellY = at.second))
        apps.forEachIndexed { i, app ->
            dao.insert(HomeItemEntity(containerId = id, packageName = app.packageName, className = app.className, page = 0, cellX = i, cellY = 0))
        }
        return id
    }

    private suspend fun assertSlotsNumbered() {
        dock().forEachIndexed { i, row ->
            assertThat(row.page).isEqualTo(0)
            assertThat(row.cellY).isEqualTo(0)
            assertThat(row.cellX).isEqualTo(i)
        }
    }

    // --- Plain app rows ---------------------------------------------------------------------------

    @Test
    fun addToDockAppendsOnceAndIgnoresDuplicates() = runTest {
        assertThat(repo.addToDock(a)).isTrue()
        assertThat(repo.addToDock(b)).isTrue()
        assertThat(repo.addToDock(a)).isFalse()

        assertThat(dockOrder()).containsExactly("a", "b").inOrder()
        assertSlotsNumbered()
    }

    @Test
    fun removeFromDockDropsOnlyThatAppAndClosesTheGap() = runTest {
        repo.addToDock(a); repo.addToDock(b); repo.addToDock(c)

        repo.removeFromDock(b)

        assertThat(dockOrder()).containsExactly("a", "c").inOrder()
        assertSlotsNumbered()
    }

    @Test
    fun addToDockAtInsertsAtTheGivenSlotAndRepositionsAnExistingRow() = runTest {
        repo.addToDock(a); repo.addToDock(b); repo.addToDock(c)

        repo.addToDockAt(d, 1)
        assertThat(dockOrder()).containsExactly("a", "d", "b", "c").inOrder()

        repo.addToDockAt(c, 0)
        assertThat(dockOrder()).containsExactly("c", "a", "d", "b").inOrder()
        assertThat(dock().map { it.id }.toSet()).hasSize(4)
        assertSlotsNumbered()
    }

    @Test
    fun addToDockAtCountsTheIndexOverTheRowsTheDockShows() = runTest {
        // "b" is uninstalled (the dock skips it), so slot 1 as the user sees it is right before "c".
        repo.addToDock(a); repo.addToDock(b); repo.addToDock(c)

        repo.addToDockAt(d, 1, shownKeys = setOf(a.key, c.key))
        assertThat(dockOrder()).containsExactly("a", "b", "d", "c").inOrder()

        // Past the last shown row: right after it, not after the hidden tail.
        repo.removeFromDock(d)
        repo.addToDock(d) // tail: a b c d, d hidden too
        repo.addToDockAt(app("e"), 2, shownKeys = setOf(a.key, c.key))
        assertThat(dockOrder()).containsExactly("a", "b", "c", "e", "d").inOrder()
        assertSlotsNumbered()
    }

    @Test
    fun reorderDockKeepsRowsHiddenByTheColumnCapAfterTheNewOrder() = runTest {
        repo.addToDock(a); repo.addToDock(b); repo.addToDock(c); repo.addToDock(d)
        val (ra, rb, rc) = dock().take(3).map { it.id }

        // The UI shows three slots and swaps the first two; "d" is beyond the cap.
        repo.reorderDock(listOf(rb, ra, rc))

        assertThat(dockOrder()).containsExactly("b", "a", "c", "d").inOrder()
        assertSlotsNumbered()
    }

    @Test
    fun reorderDockIgnoresIdsThatAreNotDockRows() = runTest {
        repo.addToDock(a); repo.addToDock(b)
        val home = insertOnHome(c, 0, 0, 0)
        val (ra, rb) = dock().map { it.id }

        repo.reorderDock(listOf(rb, home, ra, 99_999L))

        assertThat(dockOrder()).containsExactly("b", "a").inOrder()
        assertThat(homeRow(c)?.containerId).isEqualTo(HOME)
    }

    // --- Legacy key list, seeding --------------------------------------------------------------------

    @Test
    fun migrateDockFavoritesMakesRowsInKeyOrderAndIsIdempotent() = runTest {
        repo.addToDock(b) // a row that is already there keeps its place

        repo.migrateDockFavorites(listOf(a.key, b.key, c.key, "garbage", a.key))
        assertThat(dockOrder()).containsExactly("b", "a", "c").inOrder()

        repo.migrateDockFavorites(listOf(a.key, b.key, c.key))
        assertThat(dockOrder()).containsExactly("b", "a", "c").inOrder()
        assertSlotsNumbered()
    }

    @Test
    fun seedDockFillsOnlyAnEmptyDock() = runTest {
        assertThat(repo.isDockEmpty()).isTrue()
        repo.seedDock(listOf(a, b))
        assertThat(dockOrder()).containsExactly("a", "b").inOrder()
        assertThat(repo.isDockEmpty()).isFalse()

        repo.seedDock(listOf(c))
        assertThat(dockOrder()).containsExactly("a", "b").inOrder()
    }

    // --- Across the surfaces --------------------------------------------------------------------------

    @Test
    fun moveDockAppToHomePlacesItAndLeavesTheDock() = runTest {
        repo.addToDock(a); repo.addToDock(b)

        val result = repo.moveDockAppToHome(a, 0, 1, 1, columns = 4, rows = 5, folderName = "Folder")

        assertThat(result).isEqualTo(PlaceResult.Placed)
        assertThat(homeRow(a)).isNotNull()
        assertThat(dockOrder()).containsExactly("b")
        assertSlotsNumbered()
    }

    @Test
    fun moveDockAppToHomeOntoAnAppMakesAFolderThere() = runTest {
        repo.addToDock(a)
        insertOnHome(b, 0, 2, 2)

        repo.moveDockAppToHome(a, 0, 2, 2, columns = 4, rows = 5, folderName = "Folder")

        val folder = dao.getContainer(HOME).single { it.isFolder }
        assertThat(folder.cellX to folder.cellY).isEqualTo(2 to 2)
        assertThat(dao.getContainerOrdered(folder.id).map { it.packageName }).containsExactly("com.b", "com.a").inOrder()
        assertThat(dockRow(a)).isNull()
    }

    @Test
    fun moveDockAppToHomeKeepsTheDockRowWhenHomeIsFull() = runTest {
        repo.addToDock(a)
        // One page-sized widget per page the pager shows: nowhere free.
        for (page in 0 until HomeLayoutRepository.MAX_PAGES) {
            dao.insert(HomeItemEntity(page = page, cellX = 0, cellY = 0, spanX = 2, spanY = 2, appWidgetId = 100 + page, widgetProvider = "w/.W"))
        }

        val result = repo.moveDockAppToHome(a, 0, 0, 0, columns = 2, rows = 2, folderName = "Folder")

        assertThat(result).isEqualTo(PlaceResult.HomeFull)
        assertThat(dockRow(a)).isNotNull()
    }

    @Test
    fun moveHomeAppToDockInsertsAtTheSlotAndLeavesHome() = runTest {
        repo.addToDock(a); repo.addToDock(b)
        insertOnHome(c, 0, 0, 0)

        repo.moveHomeAppToDock(c, 1)

        assertThat(dockOrder()).containsExactly("a", "c", "b").inOrder()
        assertThat(homeRow(c)).isNull()
    }

    @Test
    fun moveChildToDockAppendsAndDissolvesAFolderLeftWithOne() = runTest {
        val folder = homeFolderWith(a, b)
        repo.addToDock(c)

        repo.moveChildToDock(a, folder)

        assertThat(dockOrder()).containsExactly("c", "a").inOrder()
        assertThat(dao.getById(folder)).isNull()
        assertThat(homeRow(b)?.cellX to homeRow(b)?.cellY).isEqualTo(0 to 0)
    }

    // --- Folders in the dock --------------------------------------------------------------------------

    @Test
    fun createDockFolderFoldsTheTargetSlotWithAHomeApp() = runTest {
        repo.addToDock(a); repo.addToDock(b)
        insertOnHome(c, 0, 0, 0)

        val folderId = repo.createDockFolder(target = a, dropped = c, fromContainer = HOME, name = "Folder")

        assertThat(dockOrder()).containsExactly("[Folder]", "b").inOrder()
        val folder = dao.getById(folderId)!!
        assertThat(folder.containerId).isEqualTo(DOCK)
        assertThat(folder.cellX).isEqualTo(0)
        assertThat(dao.getContainerOrdered(folderId).map { it.packageName }).containsExactly("com.a", "com.c").inOrder()
        assertThat(homeRow(c)).isNull()
        assertSlotsNumbered()
    }

    @Test
    fun createDockFolderFromAnotherDockAppClosesItsSlot() = runTest {
        repo.addToDock(a); repo.addToDock(b); repo.addToDock(c)

        repo.createDockFolder(target = c, dropped = a, fromContainer = DOCK, name = "Folder")

        assertThat(dockOrder()).containsExactly("b", "[Folder]").inOrder()
        assertSlotsNumbered()
    }

    @Test
    fun createDockFolderFromTheDrawerMakesANewChild() = runTest {
        repo.addToDock(a)

        val folderId = repo.createDockFolder(target = a, dropped = b, fromContainer = null, name = "Folder")

        assertThat(dao.getContainerOrdered(folderId).map { it.packageName }).containsExactly("com.a", "com.b").inOrder()
        assertThat(dockRow(b)).isNull()
    }

    @Test
    fun createDockFolderFromAHomeFolderChildReindexesThatFolder() = runTest {
        repo.addToDock(a)
        val source = homeFolderWith(b, c, d)

        repo.createDockFolder(target = a, dropped = b, fromContainer = source, name = "Folder")

        assertThat(dao.getContainerOrdered(source).map { it.packageName to it.cellX })
            .containsExactly("com.c" to 0, "com.d" to 1).inOrder()
    }

    @Test
    fun addToDockFolderTakesTheAppInAndNeverDuplicates() = runTest {
        repo.addToDock(a)
        val folderId = repo.createDockFolder(target = a, dropped = b, fromContainer = null, name = "Folder")
        insertOnHome(c, 0, 0, 0)

        repo.addToDockFolder(c, folderId, fromContainer = HOME)
        repo.addToDockFolder(c, folderId, fromContainer = null)

        assertThat(dao.getContainerOrdered(folderId).map { it.packageName }).containsExactly("com.a", "com.b", "com.c").inOrder()
        assertThat(homeRow(c)).isNull()
    }

    @Test
    fun aDockFolderLeftWithOneAppDissolvesIntoItsSlot() = runTest {
        repo.addToDock(a); repo.addToDock(c)
        val folderId = repo.createDockFolder(target = a, dropped = b, fromContainer = null, name = "Folder")

        repo.removeChild(b, folderId)

        assertThat(dockOrder()).containsExactly("a", "c").inOrder()
        assertThat(dao.getById(folderId)).isNull()
        assertSlotsNumbered()
    }

    @Test
    fun aDockFolderLastAppAlreadyInTheDockGoesWithTheFolder() = runTest {
        repo.addToDock(a); repo.addToDock(c)
        val folderId = repo.createDockFolder(target = c, dropped = b, fromContainer = null, name = "Folder")
        repo.addToDockFolder(a, folderId, fromContainer = null) // a: own slot AND in the folder

        repo.removeChild(b, folderId)
        repo.removeChild(c, folderId)

        assertThat(dockOrder()).containsExactly("a")
        assertThat(dao.getById(folderId)).isNull()
    }

    @Test
    fun moveOutOfADockFolderToHomeFollowsTheHomeRules() = runTest {
        repo.addToDock(a)
        val folderId = repo.createDockFolder(target = a, dropped = b, fromContainer = null, name = "Folder")
        repo.addToDockFolder(c, folderId, fromContainer = null)

        val result = repo.moveOutOfFolderTo(b, folderId, 0, 1, 1, columns = 4, rows = 5, folderName = "Folder")

        assertThat(result).isEqualTo(PlaceResult.Placed)
        assertThat(homeRow(b)?.cellX to homeRow(b)?.cellY).isEqualTo(1 to 1)
        assertThat(dao.getContainerOrdered(folderId).map { it.packageName to it.cellX })
            .containsExactly("com.a" to 0, "com.c" to 1).inOrder()
        assertThat(dao.getById(folderId)?.containerId).isEqualTo(DOCK)
    }

    @Test
    fun moveFolderToDockTakesAHomeFolderIntoASlot() = runTest {
        repo.addToDock(a); repo.addToDock(b)
        val folderId = homeFolderWith(c, d, at = 2 to 2)

        assertThat(repo.moveFolderToDock(folderId, 1)).isTrue()

        assertThat(dockOrder()).containsExactly("a", "[Pair]", "b").inOrder()
        assertThat(dao.getContainerOrdered(folderId).map { it.packageName }).containsExactly("com.c", "com.d").inOrder()
        assertThat(dao.getAt(HOME, 0, 2, 2)).isNull()
        assertSlotsNumbered()
    }

    @Test
    fun moveDockFolderToHomeOnlyOntoAFreeCell() = runTest {
        repo.addToDock(a); repo.addToDock(b)
        val folderId = repo.createDockFolder(target = a, dropped = c, fromContainer = null, name = "Folder")
        insertOnHome(d, 0, 1, 1)

        assertThat(repo.moveDockFolderToHome(folderId, 0, 1, 1)).isFalse()
        assertThat(dao.getById(folderId)?.containerId).isEqualTo(DOCK)

        assertThat(repo.moveDockFolderToHome(folderId, 0, 2, 2)).isTrue()
        val folder = dao.getById(folderId)!!
        assertThat(folder.containerId).isEqualTo(HOME)
        assertThat(Triple(folder.page, folder.cellX, folder.cellY)).isEqualTo(Triple(0, 2, 2))
        assertThat(dockOrder()).containsExactly("b")
        assertSlotsNumbered()
    }

    // --- Sweeps ---------------------------------------------------------------------------------------

    @Test
    fun anUninstallDropsTheDockRowAndRepairsADockFolder() = runTest {
        repo.addToDock(a); repo.addToDock(b)
        val folderId = repo.createDockFolder(target = b, dropped = c, fromContainer = null, name = "Folder")

        repo.removeAppRowsForPackage(a.packageName, 0L)
        repo.removeAppRowsForPackage(c.packageName, 0L)

        assertThat(dockOrder()).containsExactly("b")
        assertThat(dao.getById(folderId)).isNull()
        assertSlotsNumbered()
    }

    @Test
    fun theStartUpSweepRepairsDockFolders() = runTest {
        repo.addToDock(a)
        val folderId = repo.createDockFolder(target = a, dropped = b, fromContainer = null, name = "Folder")

        repo.removeStaleAppRows { pkg, _ -> pkg != b.packageName }

        assertThat(dao.getById(folderId)).isNull()
        assertThat(dockOrder()).containsExactly("a")
    }

    // --- Review round 1 (2026-10-30) ---------------------------------------------------------------

    @Test
    fun removingAnEmptyDockFolderClosesItsSlotSoTheNextAddFits() = runTest {
        // A folder whose apps all stopped resolving renders empty and is removed from the remove pill.
        val folderId = dao.insert(HomeItemEntity(containerId = DOCK, folderName = "Gone", page = 0, cellX = 0, cellY = 0))
        dao.insert(HomeItemEntity(containerId = folderId, packageName = "com.gone", className = "Main", page = 0, cellX = 0, cellY = 0))
        dao.insert(homeRowIn(DOCK, a, 1)); dao.insert(homeRowIn(DOCK, b, 2))

        repo.removeFolder(folderId)
        assertSlotsNumbered()
        assertThat(repo.addToDock(c)).isTrue()

        assertThat(dockOrder()).containsExactly("a", "b", "c").inOrder()
        assertSlotsNumbered()
        assertThat(dao.getContainer(folderId)).isEmpty()
    }

    @Test
    fun addToDockNeverTripsTheUniqueIndexOnAGappedDock() = runTest {
        // Slots 1 and 2 taken, 0 free (an older row set left a gap): the count would say "insert at 2".
        dao.insert(homeRowIn(DOCK, a, 1)); dao.insert(homeRowIn(DOCK, b, 2))

        assertThat(repo.addToDock(c)).isTrue()

        assertThat(dockOrder()).containsExactly("a", "b", "c").inOrder()
        assertSlotsNumbered()
    }

    @Test
    fun createDockFolderRefusesTheSameAppWhereverItComesFrom() = runTest {
        repo.addToDock(a)
        insertOnHome(a, 0, 1, 1)

        assertThat(repo.createDockFolder(target = a, dropped = a, fromContainer = null, name = "Folder")).isEqualTo(-1L)
        assertThat(repo.createDockFolder(target = a, dropped = a, fromContainer = HOME, name = "Folder")).isEqualTo(-1L)
        assertThat(repo.createDockFolder(target = a, dropped = a, fromContainer = DOCK, name = "Folder")).isEqualTo(-1L)

        assertThat(dockOrder()).containsExactly("a")
        assertThat(homeRow(a)).isNotNull()
        assertThat(dao.getAll().none { it.isFolder }).isTrue()
    }

    private fun homeRowIn(container: Long, app: AppItem, slot: Int) =
        HomeItemEntity(containerId = container, packageName = app.packageName, className = app.className, page = 0, cellX = slot, cellY = 0)
}
