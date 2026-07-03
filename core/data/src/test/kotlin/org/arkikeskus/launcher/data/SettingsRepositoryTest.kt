package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    fun `drive failure counter flips failing at the threshold and clears`() = runTest {
        val repo = newRepository()
        assertThat(repo.driveBackupFailing.first()).isFalse()

        repeat(SettingsRepository.DRIVE_FAILING_THRESHOLD - 1) { repo.registerDriveFailure() }
        assertThat(repo.driveBackupFailing.first()).isFalse()

        val count = repo.registerDriveFailure()
        assertThat(count).isEqualTo(SettingsRepository.DRIVE_FAILING_THRESHOLD)
        assertThat(repo.driveBackupFailing.first()).isTrue()

        repo.clearDriveFailures()
        assertThat(repo.driveBackupFailing.first()).isFalse()
    }

    @Test
    fun `drive failure counter is excluded from export and preserved across restore`() = runTest {
        val repo = newRepository()
        repeat(SettingsRepository.DRIVE_FAILING_THRESHOLD) { repo.registerDriveFailure() }

        assertThat(repo.exportRaw().keys).doesNotContain("drive_failure_count")

        repo.importRaw(mapOf("home_columns" to 5))
        assertThat(repo.driveBackupFailing.first()).isTrue()
    }

    @Test
    fun `app usage is excluded from export`() = runTest {
        val store = InMemoryDataStore()
        val settings = SettingsRepository(store)
        val usage = AppUsageRepository(store)
        // Volatile per-launch frecency data must not enter a backup (it changed the dedup hash on
        // every app launch, so the Drive worker uploaded a fresh copy even when the layout was
        // unchanged).
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
    fun `importRaw preserves Drive state and applies restored values`() = runTest {
        val repo = newRepository()
        // Arrange: enable Drive with known last-backup metadata.
        repo.setDriveEnabled(true)
        repo.setDriveLastBackup(123L, "testhash")

        // Act: restore a backup that does not include any Drive keys.
        repo.importRaw(mapOf("home_columns" to 5))

        // Assert: Drive enable and last-backup time survive the restore.
        assertThat(repo.driveEnabled.first()).isTrue()
        assertThat(repo.driveLastBackupTime.first()).isEqualTo(123L)
        // Assert: the restored setting was actually applied.
        assertThat(repo.settings.first().homeColumns).isEqualTo(5)
    }
}
