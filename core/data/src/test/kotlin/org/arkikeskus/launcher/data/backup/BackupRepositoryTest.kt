package org.arkikeskus.launcher.data.backup

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FakeAppRowResolver
import org.arkikeskus.launcher.data.InMemoryDataStore
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Restoring a backup end to end: the mapping, the settings import and the layout write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRepositoryTest {

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
    }

    @After
    fun tearDown() = db.close()

    private fun repo(homeItemDao: HomeItemDao = dao) =
        BackupRepository(RuntimeEnvironment.getApplication(), homeItemDao, settings, resolver)

    private fun doc(settings: Map<String, Any>, vararg items: BackupItem, mainUserSerial: Long? = null) =
        BackupDocument(BackupCodec.FORMAT, "test", 0L, settings, items.toList(), mainUserSerial)

    @Test
    fun restore_keepsOnlyAppsInstalledInTheLaunchersProfile() = runTest {
        resolver.ownSerial = 0L
        val backup = doc(
            mapOf("home_columns" to 4),
            BackupItem(1, -1, null, "a", "A", true, null, 0, 0, 0),
            BackupItem(2, -1, null, "w", "W", true, null, 0, 1, 0), // only a work-profile copy here
        )

        val result = repo().restoreDocument(backup, installedApps = listOf("a/A/0", "w/W/10"))

        assertThat(dao.getAll().map { it.key }).containsExactly("a/A/0")
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun restore_pointsSettingsAppKeysAtThisDevicesProfile() = runTest {
        resolver.ownSerial = 0L
        val backup = doc(
            mapOf("dock_favorites" to "a/A/5\nw/W/12", "left_swipe_app_key" to "a/A/5"),
            mainUserSerial = 5L,
        )

        repo().restoreDocument(backup, installedApps = listOf("a/A/0"))

        assertThat(settings.dockFavorites.first()).containsExactly("a/A/0")
        assertThat(settings.settings.first().leftSwipeAppKey).isEqualTo("a/A/0")
    }

    @Test
    fun restore_dropsShortcutsTheSystemNoLongerHas_andPinsTheRest() = runTest {
        resolver.ownSerial = 0L
        resolver.missing["p"] = setOf("gone")
        // The layout being replaced pinned a shortcut of another app: its pin is released.
        dao.insert(org.arkikeskus.launcher.data.local.HomeItemEntity(packageName = "q", shortcutId = "old", page = 0, cellX = 0, cellY = 0))
        val backup = doc(
            mapOf("home_columns" to 4),
            BackupItem(1, -1, null, "p", "", true, "kept", 0, 0, 0),
            BackupItem(2, -1, null, "p", "", true, "gone", 0, 1, 0),
        )

        val result = repo().restoreDocument(backup, installedApps = listOf("p/P/0"))

        assertThat(dao.getAll().map { it.shortcutId }).containsExactly("kept")
        assertThat(result.skipped).isEqualTo(1)
        assertThat(resolver.pins).containsExactly("p" to 0L, listOf("kept"), "q" to 0L, emptyList<String>())
    }

    // --- Write order ------------------------------------------------------------------------------

    /** Runs [beforeReplace] right before the layout write, then writes (or, with [fail], throws). */
    private class ObservedDao(
        private val inner: HomeItemDao,
        private val fail: Boolean = false,
        private val beforeReplace: suspend () -> Unit = {},
    ) : HomeItemDao by inner {
        override suspend fun replaceLayout(items: List<org.arkikeskus.launcher.data.local.HomeItemEntity>) {
            beforeReplace()
            if (fail) throw IllegalStateException("disk full")
            inner.replaceLayout(items)
        }
    }

    @Test
    fun restore_writesTheSettingsBeforeTheLayout() = runTest {
        // A death between the two writes must leave the current layout intact: the destructive
        // layout replacement is the last step.
        var columnsWhenTheLayoutWasWritten = 0
        val observed = ObservedDao(dao) { columnsWhenTheLayoutWasWritten = settings.settings.first().homeColumns }
        val backup = doc(mapOf("home_columns" to 6), BackupItem(1, -1, null, "a", "A", true, null, 0, 5, 0))

        repo(observed).restoreDocument(backup, installedApps = listOf("a/A/0"))

        assertThat(columnsWhenTheLayoutWasWritten).isEqualTo(6)
        assertThat(dao.getAll().map { it.key }).containsExactly("a/A/0")
    }

    @Test
    fun restore_putsTheSettingsBackWhenTheLayoutWriteFails() = runTest {
        settings.setHomeColumns(5)
        settings.addToDock("x/X/0")
        dao.insert(org.arkikeskus.launcher.data.local.HomeItemEntity(packageName = "x", className = "X", page = 0, cellX = 0, cellY = 0))
        val backup = doc(mapOf("home_columns" to 6), BackupItem(1, -1, null, "a", "A", true, null, 0, 0, 0))

        val failure = runCatching { repo(ObservedDao(dao, fail = true)).restoreDocument(backup, listOf("a/A/0")) }

        assertThat(failure.isFailure).isTrue()
        assertThat(settings.settings.first().homeColumns).isEqualTo(5)
        assertThat(settings.dockFavorites.first()).containsExactly("x/X/0")
        assertThat(dao.getAll().map { it.key }).containsExactly("x/X/0")
    }
}
