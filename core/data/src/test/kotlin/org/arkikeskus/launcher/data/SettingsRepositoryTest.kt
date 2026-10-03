package org.arkikeskus.launcher.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

    @Test
    fun `upgrade repairs page counters restored as Long by an older version before reading`() = runTest {
        val store = InMemoryDataStore()
        store.edit {
            it[longPreferencesKey("home_page")] = 2L
            it[longPreferencesKey("home_page_count")] = 4L
        }
        val repo = SettingsRepository(store)
        assertThat(repo.settings.first().homePage).isEqualTo(2)
        assertThat(repo.settings.first().homePageCount).isEqualTo(4)
        assertThat(store.data.first()[intPreferencesKey("home_page")]).isEqualTo(2)
        assertThat(store.data.first()[intPreferencesKey("home_page_count")]).isEqualTo(4)
        assertThat(SettingsRepository(store).settings.first().homePage).isEqualTo(2)
    }

    @Test
    fun `legacy page counters clamp before conversion so large Longs cannot wrap`() = runTest {
        val store = InMemoryDataStore()
        store.edit {
            it[longPreferencesKey("home_page")] = Long.MIN_VALUE
            it[longPreferencesKey("home_page_count")] = Long.MAX_VALUE
        }
        val settings = SettingsRepository(store).settings.first()
        assertThat(settings.homePage).isEqualTo(0)
        assertThat(settings.homePageCount).isEqualTo(HomeLayoutRepository.MAX_PAGES)
    }

    private fun newRepository() = SettingsRepository(InMemoryDataStore())

    @Test
    fun widgetBackgroundPreservesOldDefaultAndRoundTripsThroughBackup() = runTest {
        val source = newRepository()
        assertThat(source.settings.first().widgetTonalBackground).isFalse()
        source.setWidgetTonalBackground(true)
        val restored = newRepository()
        restored.importRaw(source.exportRaw())
        assertThat(restored.settings.first().widgetTonalBackground).isTrue()
        restored.importRaw(mapOf("widget_tonal_background" to "invalid"))
        assertThat(restored.settings.first().widgetTonalBackground).isFalse()
    }

    @Test
    fun networkPlaceNamesIsOptInAndRoundTripsThroughBackup() = runTest {
        val source = newRepository()
        assertThat(source.settings.first().networkPlaceNames).isFalse()
        source.setNetworkPlaceNames(true)
        val restored = newRepository()
        restored.importRaw(source.exportRaw())
        assertThat(restored.settings.first().networkPlaceNames).isTrue()
        restored.importRaw(mapOf("network_place_names" to "invalid"))
        assertThat(restored.settings.first().networkPlaceNames).isFalse()
    }

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
    fun `addToDockAt counts the drop index over the favorites the dock shows`() = runTest {
        val repo = newRepository()
        listOf("ghost", "a", "b").forEach { repo.addToDock(it) }

        // An uninstalled favorite isn't shown: the dock reads [a, b] and the drop lands between them.
        repo.addToDockAt("x", index = 1, resolvableKeys = setOf("a", "b", "x"))

        assertThat(repo.dockFavorites.first()).containsExactly("ghost", "a", "x", "b").inOrder()
    }

    @Test
    fun `addToDockAt at the end of the shown dock goes after its last shown favorite`() = runTest {
        val repo = newRepository()
        listOf("a", "ghost", "b", "ghost2").forEach { repo.addToDock(it) }

        repo.addToDockAt("x", index = 2, resolvableKeys = setOf("a", "b"))
        repo.addToDockAt("y", index = 0, resolvableKeys = setOf("a", "b"))

        assertThat(repo.dockFavorites.first()).containsExactly("y", "a", "ghost", "b", "x", "ghost2").inOrder()
    }

    @Test
    fun `addToDockAt repositions a shown favorite past an uninstalled one`() = runTest {
        val repo = newRepository()
        listOf("ghost", "a", "b", "c").forEach { repo.addToDock(it) }

        repo.addToDockAt("c", index = 0, resolvableKeys = setOf("a", "b", "c"))

        assertThat(repo.dockFavorites.first()).containsExactly("ghost", "c", "a", "b").inOrder()
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
    fun `doubleTapToLock defaults to false and round-trips`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().doubleTapToLock).isFalse()

        repo.setDoubleTapToLock(true)
        assertThat(repo.settings.first().doubleTapToLock).isTrue()

        repo.setDoubleTapToLock(false)
        assertThat(repo.settings.first().doubleTapToLock).isFalse()
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
    fun `held notifications survive restoring a backup and recreating the repository`() = runTest {
        val store = InMemoryDataStore()
        val repo = SettingsRepository(store)
        val held = mapOf(
            "0|example.mail|42|message|10001" to 1_790_752_800_000L,
            "0|example.chat|7|conversation|10002" to 1_790_770_800_000L,
        )
        repo.setHeldNotifications(held)
        repo.setHomeColumns(5)
        val backup = repo.exportRaw()
        assertThat(backup).doesNotContainKey("people_held_notifications")

        repo.setHomeColumns(4)
        repo.importRaw(backup)

        // A new listener must be able to identify its snoozes from the persisted state after restore.
        val reopened = SettingsRepository(store)
        assertThat(reopened.heldNotifications.first()).containsExactlyEntriesIn(held)
        assertThat(reopened.settings.first().homeColumns).isEqualTo(5)
    }

    @Test
    fun `restoring foreign held notifications preserves this devices own registry`() = runTest {
        val store = InMemoryDataStore()
        val repo = SettingsRepository(store)
        val held = mapOf("local-notification" to 1_790_752_800_000L)
        repo.setHeldNotifications(held)

        repo.importRaw(
            mapOf(
                "people_held_notifications" to "foreign-notification\t1790770800000",
                "home_columns" to 5,
            ),
        )

        val reopened = SettingsRepository(store)
        assertThat(reopened.heldNotifications.first()).containsExactlyEntriesIn(held)
        assertThat(reopened.settings.first().homeColumns).isEqualTo(5)
    }

    @Test
    fun `restoring a foreign registry never creates held notifications on this device`() = runTest {
        val repo = newRepository()

        repo.importRaw(mapOf("people_held_notifications" to "foreign-notification\t1790770800000"))

        assertThat(repo.heldNotifications.first()).isEmpty()
        assertThat(repo.exportRaw()).doesNotContainKey("people_held_notifications")
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

    @Test
    fun `twoLineHomeLabels defaults to false and round-trips`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().twoLineHomeLabels).isFalse()

        repo.setTwoLineHomeLabels(true)
        assertThat(repo.settings.first().twoLineHomeLabels).isTrue()

        repo.setTwoLineHomeLabels(false)
        assertThat(repo.settings.first().twoLineHomeLabels).isFalse()
    }

    @Test
    fun `twoLineDrawerLabels defaults to true and round-trips`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().twoLineDrawerLabels).isTrue()

        repo.setTwoLineDrawerLabels(false)
        assertThat(repo.settings.first().twoLineDrawerLabels).isFalse()

        repo.setTwoLineDrawerLabels(true)
        assertThat(repo.settings.first().twoLineDrawerLabels).isTrue()
    }

    @Test
    fun `the two-line label switches survive a backup round-trip and reject a wrong type`() = runTest {
        val repo = newRepository()
        repo.setTwoLineHomeLabels(true)
        repo.setTwoLineDrawerLabels(false)
        val exported = repo.exportRaw()
        assertThat(exported).containsEntry("two_line_home_labels", true)
        assertThat(exported).containsEntry("two_line_drawer_labels", false)

        val restored = newRepository()
        restored.importRaw(exported)
        assertThat(restored.settings.first().twoLineHomeLabels).isTrue()
        assertThat(restored.settings.first().twoLineDrawerLabels).isFalse()

        // Registered as boolean keys: a wrong-typed value is dropped instead of poisoning the store.
        restored.importRaw(mapOf("two_line_home_labels" to "yes", "two_line_drawer_labels" to 1))
        val s = restored.settings.first() // must not throw ClassCastException
        assertThat(s.twoLineHomeLabels).isFalse()
        assertThat(s.twoLineDrawerLabels).isTrue()
    }
}
