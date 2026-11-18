package org.arkikeskus.launcher.data

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.local.inHomeProfile
import org.arkikeskus.launcher.data.profile.SwitchableProfile
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the phone has one of, however many home profiles there are: the widget host's ids, the system's set
 * of pinned shortcuts per app, and the installed apps. A profile that is not showing still owns its widgets
 * and its pinned shortcuts, and an app that is uninstalled is gone from every profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeProfilesLayoutTest {
    private lateinit var db: LauncherDatabase
    private val active = SwitchableProfile()
    private lateinit var dao: HomeItemDao
    private val resolver = FakeAppRowResolver()
    private lateinit var repo: HomeLayoutRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao(active)
        repo = HomeLayoutRepository(db, dao, resolver)
    }

    @After fun tearDown() = db.close()

    private fun app(pkg: String, cellX: Int, container: Long = HOME, cellY: Int = 0) =
        HomeItemEntity(containerId = container, packageName = pkg, className = "Main", page = 0, cellX = cellX, cellY = cellY)

    private fun widget(pkg: String, id: Int?, cellX: Int) = HomeItemEntity(
        packageName = pkg, className = "", page = 0, cellX = cellX, cellY = 1, spanX = 2, spanY = 1,
        appWidgetId = id, widgetProvider = "$pkg/.Widget",
    )

    private fun shortcut(pkg: String, id: String, cellX: Int) =
        HomeItemEntity(packageName = pkg, className = "", page = 0, cellX = cellX, cellY = 2, shortcutId = id)

    private suspend fun rowsOf(profile: Long) = inHomeProfile(profile) { dao.getAll() }

    @Test fun theSweepSeesEveryProfilesWidgets() = runTest {
        dao.insert(widget("com.clock", 11, cellX = 0))
        inHomeProfile(2) { dao.insert(widget("com.cal", 12, cellX = 0)) }

        assertThat(repo.boundWidgetIds()).containsExactly(11, 12)

        // The home screen's sweep frees a host id only when no row of ANY profile holds it.
        val freed = ArrayList<Int>()
        PendingWidgetIds().sweepHostIds(
            hostIds = { intArrayOf(11, 12, 13) },
            rowIds = { repo.boundWidgetIds() },
            keep = { emptySet() },
            delete = { freed += it },
        )
        assertThat(freed).containsExactly(13)
    }

    @Test fun aStaleWidgetIdIsUnboundInEveryProfile() = runTest {
        dao.insert(widget("com.clock", 11, cellX = 0))
        inHomeProfile(2) {
            dao.insert(widget("com.cal", 12, cellX = 0))
            dao.insert(widget("com.notes", 13, cellX = 2))
        }

        // After a move to another phone the host knows none of the old ids but 13.
        repo.unbindStaleWidgets(validIds = setOf(13))

        assertThat(rowsOf(1).single().appWidgetId).isNull()
        assertThat(rowsOf(2).associate { it.packageName to it.appWidgetId }).containsExactly("com.cal", null, "com.notes", 13)
    }

    @Test fun anUninstallCleansEveryProfile() = runTest {
        dao.insert(app("com.gone", 0))
        dao.insert(app("com.stays", 1))
        inHomeProfile(2) {
            dao.insert(app("com.gone", 0))
            dao.insert(app("com.gone", 0, container = DOCK))
            dao.insert(widget("com.gone", 21, cellX = 0))
            dao.insert(app("com.other", 2))
        }

        val removed = repo.removeAppRowsForPackage("com.gone", userSerial = 0)

        assertThat(removed).isEqualTo(4)
        assertThat(rowsOf(1).map { it.packageName }).containsExactly("com.stays")
        assertThat(rowsOf(2).map { it.packageName }).containsExactly("com.other")
    }

    @Test fun theStartUpSweepRemovesAnUninstalledAppFromEveryProfile() = runTest {
        dao.insert(app("com.gone", 0))
        inHomeProfile(2) {
            dao.insert(app("com.gone", 3))
            dao.insert(app("com.stays", 1))
        }

        val changed = repo.removeStaleAppRows { pkg, _ -> pkg != "com.gone" }

        assertThat(changed).isEqualTo(2)
        assertThat(rowsOf(1)).isEmpty()
        assertThat(rowsOf(2).map { it.packageName }).containsExactly("com.stays")
    }

    @Test fun aShortcutTheSystemLostGoesFromEveryProfile() = runTest {
        dao.insert(shortcut("com.chat", "old", cellX = 0))
        dao.insert(shortcut("com.chat", "kept", cellX = 1))
        inHomeProfile(2) { dao.insert(shortcut("com.chat", "old", cellX = 0)) }
        resolver.missing["com.chat"] = setOf("old")

        val removed = repo.removeMissingShortcuts("com.chat", userSerial = 0)

        assertThat(removed).isEqualTo(2)
        assertThat(rowsOf(1).map { it.shortcutId }).containsExactly("kept")
        assertThat(rowsOf(2)).isEmpty()
    }

    @Test fun aShortcutStillOnAnotherProfileStaysInThePinnedSet() = runTest {
        val here = dao.insert(shortcut("com.chat", "new", cellX = 0))
        inHomeProfile(2) {
            dao.insert(shortcut("com.chat", "new", cellX = 0))
            dao.insert(shortcut("com.chat", "group", cellX = 1))
        }

        // What the system should hold for the app is what every profile has of it.
        assertThat(repo.homeShortcutIds("com.chat", 0)).containsExactly("new", "group")

        val remaining = repo.removeShortcut(here)!!

        assertThat(remaining.removedShortcutId).isEqualTo("new")
        // "new" is gone from this profile and stays pinned for the other one.
        assertThat(remaining.shortcutIds).containsExactly("new", "group")
        assertThat(rowsOf(1)).isEmpty()
        // Another user of the phone (a work profile's app) is another set.
        assertThat(repo.homeShortcutIds("com.chat", 10)).isEmpty()
    }

    @Test fun aCopyHasTheSameCellsNewIdsAndPlaceholderWidgets() = runTest {
        dao.insert(app("com.mail", 0))
        dao.insert(app("com.phone", 0, container = DOCK))
        dao.insert(widget("com.clock", 11, cellX = 0))
        dao.insert(shortcut("com.chat", "new", cellX = 1))
        dao.insert(HomeItemEntity(page = 1, cellX = 0, cellY = 0, spanX = 4, spanY = 2, builtinType = HomeItemEntity.BUILTIN_SMARTSPACE))

        repo.copyProfile(from = 1, to = 2)

        val source = rowsOf(1)
        val copy = rowsOf(2)
        fun List<HomeItemEntity>.shape() = map { it.copy(id = 0, homeProfileId = 0, appWidgetId = null) }.toSet()
        assertThat(copy.shape()).isEqualTo(source.shape())
        assertThat(copy.map { it.id }.intersect(source.map { it.id }.toSet())).isEmpty()
        // The widget is there with its size and its provider, to be set up again: one id shows in one place.
        val widget = copy.single { it.widgetProvider != null }
        assertThat(widget.appWidgetId).isNull()
        assertThat(widget.spanX).isEqualTo(2)
        assertThat(source.single { it.widgetProvider != null }.appWidgetId).isEqualTo(11)
        val ids = dao.getAllOfEveryProfile().mapNotNull { it.appWidgetId }
        assertThat(ids).containsNoDuplicates()
    }

    @Test fun aCopiedFoldersChildrenFollowTheNewFolder() = runTest {
        val folder = dao.insert(HomeItemEntity(folderName = "Tools", page = 0, cellX = 2, cellY = 3))
        dao.insert(app("com.calc", 0, container = folder))
        dao.insert(app("com.notes", 1, container = folder))
        val dockFolder = dao.insert(HomeItemEntity(containerId = DOCK, folderName = "Talk", page = 0, cellX = 1, cellY = 0))
        dao.insert(app("com.chat", 0, container = dockFolder))
        dao.insert(app("com.sms", 1, container = dockFolder))

        val newIds = repo.copyProfile(from = 1, to = 2)

        val copy = rowsOf(2)
        val newFolder = copy.single { it.folderName == "Tools" }
        val newDockFolder = copy.single { it.folderName == "Talk" }
        assertThat(newIds).containsExactly(folder, newFolder.id, dockFolder, newDockFolder.id)
        assertThat(newFolder.id).isNotEqualTo(folder)
        assertThat(copy.filter { it.containerId == newFolder.id }.map { it.packageName }).containsExactly("com.calc", "com.notes")
        assertThat(copy.filter { it.containerId == newDockFolder.id }.map { it.packageName }).containsExactly("com.chat", "com.sms")
        // No child of the copy points at a folder of the source.
        assertThat(copy.map { it.containerId }).containsNoneOf(folder, dockFolder)
        // The source is as it was.
        assertThat(rowsOf(1).filter { it.containerId == folder }).hasSize(2)
    }

    @Test fun aCopyReplacesWhatTheTargetHad() = runTest {
        dao.insert(app("com.mail", 0))
        inHomeProfile(2) { dao.insert(app("com.old", 0)) }

        repo.copyProfile(from = 1, to = 2)

        assertThat(rowsOf(2).map { it.packageName }).containsExactly("com.mail")
    }

    @Test fun deletingAProfileRemovesOnlyItsRowsAndNamesThePackagesWithShortcuts() = runTest {
        dao.insert(app("com.mail", 0))
        dao.insert(shortcut("com.chat", "new", cellX = 1))
        inHomeProfile(2) {
            dao.insert(app("com.work", 0))
            dao.insert(widget("com.cal", 12, cellX = 0))
            dao.insert(shortcut("com.chat", "group", cellX = 1))
            dao.insert(shortcut("com.maps", "home", cellX = 2))
        }

        val changed = repo.deleteProfile(2)

        assertThat(changed).containsExactly("com.chat" to 0L, "com.maps" to 0L)
        assertThat(rowsOf(2)).isEmpty()
        assertThat(rowsOf(1)).hasSize(2)
        // Its widget's id is no longer held, so the next sweep frees it; its shortcuts left the sets.
        assertThat(repo.boundWidgetIds()).isEmpty()
        assertThat(repo.homeShortcutIds("com.chat", 0)).containsExactly("new")
        assertThat(repo.homeShortcutIds("com.maps", 0)).isEmpty()
    }
}
