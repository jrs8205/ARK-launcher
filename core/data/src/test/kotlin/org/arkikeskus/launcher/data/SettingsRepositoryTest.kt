package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Test

/**
 * JVM unit tests for the dock-favorites merge logic. Backed by an in-memory [DataStore] fake (no
 * file I/O), so the tests are fast, deterministic, and platform-independent.
 */
class SettingsRepositoryTest {

    private fun newRepository() = SettingsRepository(InMemoryDataStore())

    @Test
    fun `reorderVisibleDock keeps favorites hidden by the column cap`() = runTest {
        val repo = newRepository()
        listOf("a", "b", "c", "d", "e", "f").forEach { repo.addToDock(it) }

        // Only the first 4 are visible (dockColumns); reorder those, leaving e & f hidden.
        repo.reorderVisibleDock(listOf("d", "c", "b", "a"))

        assertThat(repo.dockFavorites.first())
            .containsExactly("d", "c", "b", "a", "e", "f").inOrder()
    }

    @Test
    fun `reorderVisibleDock does not introduce duplicates`() = runTest {
        val repo = newRepository()
        listOf("a", "b", "c").forEach { repo.addToDock(it) }

        // A duplicate in the visible list must be collapsed, and the tail kept once.
        repo.reorderVisibleDock(listOf("b", "b", "a"))

        assertThat(repo.dockFavorites.first()).containsExactly("b", "a", "c").inOrder()
    }

    @Test
    fun `addToDock appends once and ignores duplicates`() = runTest {
        val repo = newRepository()
        repo.addToDock("a")
        repo.addToDock("a")
        repo.addToDock("b")

        assertThat(repo.dockFavorites.first()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `removeFromDock drops only the given key`() = runTest {
        val repo = newRepository()
        listOf("a", "b", "c").forEach { repo.addToDock(it) }

        repo.removeFromDock("b")

        assertThat(repo.dockFavorites.first()).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `addToDockAt inserts at the given index`() = runTest {
        val repo = newRepository()
        listOf("a", "b", "c").forEach { repo.addToDock(it) }

        repo.addToDockAt("x", index = 1)

        assertThat(repo.dockFavorites.first()).containsExactly("a", "x", "b", "c").inOrder()
    }

    @Test
    fun `addToDockAt repositions an existing key without duplicating`() = runTest {
        val repo = newRepository()
        listOf("a", "b", "c").forEach { repo.addToDock(it) }

        repo.addToDockAt("c", index = 0)

        assertThat(repo.dockFavorites.first()).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `searchContacts defaults to false and round-trips`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().searchContacts).isFalse()

        repo.setSearchContacts(true)
        assertThat(repo.settings.first().searchContacts).isTrue()

        repo.setSearchContacts(false)
        assertThat(repo.settings.first().searchContacts).isFalse()
    }

    @Test
    fun `leftSwipeAppKey defaults to blank and round-trips`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().leftSwipeAppKey).isEmpty()

        repo.setLeftSwipeAppKey("com.example/Main/0")
        assertThat(repo.settings.first().leftSwipeAppKey).isEqualTo("com.example/Main/0")

        // null clears back to blank (None / gesture disabled).
        repo.setLeftSwipeAppKey(null)
        assertThat(repo.settings.first().leftSwipeAppKey).isEmpty()
    }

    @Test
    fun `desktopLocked defaults to false and round-trips`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().desktopLocked).isFalse()

        repo.setDesktopLocked(true)
        assertThat(repo.settings.first().desktopLocked).isTrue()

        repo.setDesktopLocked(false)
        assertThat(repo.settings.first().desktopLocked).isFalse()
    }

    @Test
    fun `showFrequentApps defaults to false and round-trips`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().showFrequentApps).isFalse()

        repo.setShowFrequentApps(true)
        assertThat(repo.settings.first().showFrequentApps).isTrue()

        repo.setShowFrequentApps(false)
        assertThat(repo.settings.first().showFrequentApps).isFalse()
    }

    @Test
    fun `app usage is excluded from export`() = runTest {
        val store = InMemoryDataStore()
        val settings = SettingsRepository(store)
        val usage = AppUsageRepository(store)
        // Volatile per-launch frecency data is device-local and must not enter a backup.
        usage.recordLaunch("com.example/Main/0")

        assertThat(settings.exportRaw().keys).doesNotContain(AppUsageRepository.USAGE_KEY)
    }

    @Test
    fun `app usage survives a restore that omits it`() = runTest {
        val store = InMemoryDataStore()
        val settings = SettingsRepository(store)
        val usage = AppUsageRepository(store)
        usage.recordLaunch("com.example/Main/0")

        // Restoring a backup that no longer carries app_usage must not wipe this device's stats.
        settings.importRaw(mapOf("home_columns" to 5))

        assertThat(usage.usage.first()).containsKey("com.example/Main/0")
        assertThat(settings.settings.first().homeColumns).isEqualTo(5)
    }

    @Test
    fun `importRaw preserves device-local state and applies restored values`() = runTest {
        val repo = newRepository()
        repo.setLocalLastBackup(123L)

        repo.importRaw(mapOf("home_columns" to 5))

        // The device's own last-export timestamp survives the restore; the value applies.
        assertThat(repo.localLastBackupTime.first()).isEqualTo(123L)
        assertThat(repo.settings.first().homeColumns).isEqualTo(5)
    }

    @Test
    fun `importRaw tolerates a legacy Drive-era backup without importing its keys`() = runTest {
        val repo = newRepository()
        // A ≤0.7.11 backup may contain Drive/updater bookkeeping (both features were removed in
        // 0.7.12); the names must be dropped silently and the rest of the restore must apply.
        repo.importRaw(
            mapOf(
                "drive_backup_enabled" to true,
                "auto_update_enabled" to false,
                "update_last_notified_version" to "0.7.11",
                "home_columns" to 5,
            ),
        )

        assertThat(repo.settings.first().homeColumns).isEqualTo(5)
        assertThat(repo.exportRaw().keys).containsNoneOf("drive_backup_enabled", "auto_update_enabled")
    }

    @Test
    fun `homeRows defaults to 6, persists and coerces to its range`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().homeRows).isEqualTo(6)

        repo.setHomeRows(7)
        assertThat(repo.settings.first().homeRows).isEqualTo(7)

        repo.setHomeRows(99)
        assertThat(repo.settings.first().homeRows).isEqualTo(SettingsRepository.MAX_ROWS)

        repo.setHomeRows(1)
        assertThat(repo.settings.first().homeRows).isEqualTo(SettingsRepository.MIN_ROWS)
    }

    @Test
    fun `notificationWidgetCountStyle defaults to number and round-trips`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().notificationWidgetCountStyle)
            .isEqualTo(LauncherSettings.COUNT_NUMBER)

        repo.setNotificationWidgetCountStyle(LauncherSettings.COUNT_DOT)
        assertThat(repo.settings.first().notificationWidgetCountStyle)
            .isEqualTo(LauncherSettings.COUNT_DOT)
    }

    @Test
    fun `notificationWidgetCountStyle falls back to number on an unknown stored value`() = runTest {
        val repo = newRepository()
        repo.setNotificationWidgetCountStyle("garbage")
        assertThat(repo.settings.first().notificationWidgetCountStyle)
            .isEqualTo(LauncherSettings.COUNT_NUMBER)
    }

    @Test
    fun `importRaw ignores a wrong-typed value for a known key`() = runTest {
        val repo = newRepository()
        repo.importRaw(
            mapOf(
                "home_columns" to true,        // boolean for an int key -> must be dropped
                "show_weather" to 7,           // number for a boolean key -> must be dropped
                "drawer_columns" to 6,         // correct type -> applied
            ),
        )
        val s = repo.settings.first()          // must not throw ClassCastException
        assertThat(s.homeColumns).isEqualTo(4) // default
        assertThat(s.showWeather).isTrue()     // default
        assertThat(s.drawerColumns).isEqualTo(6)
    }

    @Test
    fun `importRaw never imports device-local bookkeeping keys`() = runTest {
        val repo = newRepository()
        repo.importRaw(mapOf("drive_backup_enabled" to "not-a-boolean", "app_usage" to 12345))
        // Settings stay readable (no wrong-typed value landed) and the name never re-exports.
        assertThat(repo.settings.first().homeColumns).isEqualTo(4) // default, no crash
        assertThat(repo.exportRaw().keys).doesNotContain("drive_backup_enabled")
    }

    @Test
    fun `drawer folder names with tabs and newlines round-trip without corrupting the record`() = runTest {
        val repo = newRepository()
        val id = repo.createDrawerFolder("Fun\tstuff\nrow2")
        repo.addAppsToDrawerFolder(id, listOf("com.a/A/0", "com.b/B/0"))
        val folder = repo.drawerFolders.first().single()
        assertThat(folder.name).isEqualTo("Fun stuff row2")
        assertThat(folder.appKeys).containsExactly("com.a/A/0", "com.b/B/0").inOrder()
    }
}
