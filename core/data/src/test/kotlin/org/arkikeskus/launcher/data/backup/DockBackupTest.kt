package org.arkikeskus.launcher.data.backup

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FakeAppRowResolver
import org.arkikeskus.launcher.data.InMemoryDataStore
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The dock in a backup (format 4): its apps and folders travel as rows of the [DOCK] container with
 * the same rules as home's rows, and a file of format 3 or older brings its dock as the
 * `dock_favorites` key list, which restores as rows and never stays as a key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DockBackupTest {

    private lateinit var db: LauncherDatabase
    private lateinit var dao: HomeItemDao
    private val settings = SettingsRepository(InMemoryDataStore())
    private val resolver = FakeAppRowResolver()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao()
        resolver.ownSerial = 0L
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = BackupRepository(RuntimeEnvironment.getApplication(), dao, settings, resolver)

    private fun doc(settings: Map<String, Any>, vararg items: BackupItem, format: Int = BackupCodec.FORMAT, mainUserSerial: Long? = 0L) =
        BackupDocument(format, "test", 0L, settings, items.toList(), mainUserSerial)

    private fun dockApp(id: Long, pkg: String, slot: Int) = BackupItem(id, DOCK, null, pkg, "Main", true, null, 0, slot, 0)

    private fun dockFolder(id: Long, name: String, slot: Int) = BackupItem(id, DOCK, name, "", "", true, null, 0, slot, 0)

    private fun child(id: Long, folderId: Long, pkg: String, index: Int) = BackupItem(id, folderId, null, pkg, "Main", true, null, 0, index, 0)

    private suspend fun dockOrder() = dao.getContainerOrdered(DOCK).map { if (it.isFolder) "[${it.folderName}]" else it.packageName }

    private suspend fun assertSlotsNumbered() {
        dao.getContainerOrdered(DOCK).forEachIndexed { i, row ->
            assertThat(Triple(row.page, row.cellX, row.cellY)).isEqualTo(Triple(0, i, 0))
        }
    }

    @Test
    fun exportCarriesTheDockRowsAndNoFavouritesKey() = runTest {
        dao.insert(HomeItemEntity(containerId = DOCK, packageName = "a", className = "Main", page = 0, cellX = 0, cellY = 0))
        val folder = dao.insert(HomeItemEntity(containerId = DOCK, folderName = "Tools", page = 0, cellX = 1, cellY = 0))
        dao.insert(HomeItemEntity(containerId = folder, packageName = "b", className = "Main", page = 0, cellX = 0, cellY = 0))

        val exported = repo().exportDocument(0L, "test")

        assertThat(exported.format).isEqualTo(4)
        assertThat(exported.homeItems.map { it.containerId to it.packageName }).containsExactly(DOCK to "a", DOCK to "", folder to "b")
        assertThat(exported.settings).doesNotContainKey("dock_favorites")
    }

    @Test
    fun restoreKeepsDockAppsAndFoldersWithTheirChildrenAndRenumbersTheSlots() = runTest {
        val backup = doc(
            mapOf("home_columns" to 4),
            dockApp(1, "a", 0),
            dockApp(2, "gone", 1), // not installed here: its slot closes
            dockFolder(3, "Tools", 2),
            child(4, 3, "b", 0),
            child(5, 3, "c", 1),
            dockApp(6, "d", 7), // a slot gap in the file is harmless
        )

        val result = repo().restoreDocument(backup, installedApps = listOf("a/Main/0", "b/Main/0", "c/Main/0", "d/Main/0"))

        assertThat(dockOrder()).containsExactly("a", "[Tools]", "d").inOrder()
        assertSlotsNumbered()
        assertThat(dao.getContainerOrdered(3).map { it.packageName }).containsExactly("b", "c").inOrder()
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun restoreDropsWhatTheDockCannotHoldAndDuplicatesOfOneApp() = runTest {
        val backup = doc(
            mapOf("home_columns" to 4),
            dockApp(1, "a", 0),
            dockApp(2, "a", 1), // the same app twice in the dock
            BackupItem(3, DOCK, null, "a", "", true, "shortcut", 0, 2, 0), // a shortcut: never in the dock
            BackupItem(4, DOCK, null, "", "", true, null, 0, 3, 0, spanX = 2, spanY = 1, widgetProvider = "w/.W"),
            BackupItem(5, HOME, null, "a", "Main", true, null, 0, 0, 0), // on home too: allowed
        )

        repo().restoreDocument(backup, installedApps = listOf("a/Main/0"))

        assertThat(dockOrder()).containsExactly("a")
        assertThat(dao.getContainer(HOME).map { it.packageName }).containsExactly("a")
    }

    @Test
    fun aDockFolderRestoredWithOneChildDissolvesIntoItsSlot() = runTest {
        val backup = doc(
            mapOf("home_columns" to 4),
            dockApp(1, "a", 0),
            dockFolder(2, "Tools", 1),
            child(3, 2, "b", 0),
            child(4, 2, "gone", 1),
            dockApp(5, "c", 2),
        )

        repo().restoreDocument(backup, installedApps = listOf("a/Main/0", "b/Main/0", "c/Main/0"))

        assertThat(dockOrder()).containsExactly("a", "b", "c").inOrder()
        assertSlotsNumbered()
        assertThat(dao.getAll().none { it.isFolder }).isTrue()
    }

    @Test
    fun aDockFolderWhoseLastChildIsAlreadyInTheDockGoesWithoutIt() = runTest {
        val backup = doc(
            mapOf("home_columns" to 4),
            dockApp(1, "a", 0),
            dockFolder(2, "Tools", 1),
            child(3, 2, "a", 0),
            child(4, 2, "gone", 1),
        )

        repo().restoreDocument(backup, installedApps = listOf("a/Main/0"))

        assertThat(dockOrder()).containsExactly("a")
        assertThat(dao.getAll()).hasSize(1)
    }

    @Test
    fun anOlderFilesFavouritesKeyRestoresAsDockRowsInKeyOrder() = runTest {
        // Written by 0.9.6 on a device whose main profile was serial 5: the keys follow the home rows' remapping.
        val backup = doc(
            mapOf("home_columns" to 4, "dock_favorites" to "b/Main/5\ngone/Main/5\na/Main/5\nw/Main/12"),
            BackupItem(1, HOME, null, "a", "Main", true, null, 0, 0, 0),
            format = 3,
            mainUserSerial = 5L,
        )

        repo().restoreDocument(backup, installedApps = listOf("a/Main/0", "b/Main/0", "w/Main/10"))

        assertThat(dockOrder()).containsExactly("b", "a").inOrder()
        assertSlotsNumbered()
        assertThat(dao.getContainer(HOME).map { it.packageName }).containsExactly("a")
        assertThat(settings.exportRaw()).doesNotContainKey("dock_favorites")
        assertThat(settings.legacyDockFavorites()).isEmpty()
    }

    @Test
    fun aFavouritesKeyInANewerFileIsDroppedWhenTheFileHasDockRows() = runTest {
        val backup = doc(
            mapOf("home_columns" to 4, "dock_favorites" to "b/Main/0"),
            dockApp(1, "a", 0),
        )

        repo().restoreDocument(backup, installedApps = listOf("a/Main/0", "b/Main/0"))

        assertThat(dockOrder()).containsExactly("a")
        assertThat(settings.legacyDockFavorites()).isEmpty()
    }

    @Test
    fun aFailedLayoutWriteLeavesTheDockRowsAsTheyWere() = runTest {
        dao.insert(HomeItemEntity(containerId = DOCK, packageName = "x", className = "X", page = 0, cellX = 0, cellY = 0))
        val failing = object : HomeItemDao by dao {
            override suspend fun replaceLayout(items: List<HomeItemEntity>) = throw IllegalStateException("disk full")
        }
        val backup = doc(mapOf("home_columns" to 4), dockApp(1, "a", 0))

        val failure = runCatching {
            BackupRepository(RuntimeEnvironment.getApplication(), failing, settings, resolver).restoreDocument(backup, listOf("a/Main/0"))
        }

        assertThat(failure.isFailure).isTrue()
        assertThat(dockOrder()).containsExactly("x")
        assertThat(settings.settings.first().homeColumns).isEqualTo(4)
    }
}
