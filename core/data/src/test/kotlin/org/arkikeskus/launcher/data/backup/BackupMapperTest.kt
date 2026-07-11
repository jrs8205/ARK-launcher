package org.arkikeskus.launcher.data.backup

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.Test

class BackupMapperTest {

    /** Defaults shared by most cases: a 4-column grid on the main profile (serial 42). */
    private fun toEntities(
        items: List<BackupItem>,
        mainUserSerial: Long = 42L,
        installedAppKeys: Set<String> = emptySet(),
        installedPackages: Set<String> = emptySet(),
        widgetPackages: Set<String> = emptySet(),
        columns: Int = 4,
    ) = BackupMapper.toEntities(items, mainUserSerial, installedAppKeys, installedPackages, widgetPackages, columns)

    @Test
    fun toBackupItems_includes_widgets_with_span_and_provider() {
        val entities = listOf(
            HomeItemEntity(id = 1, packageName = "com.a", className = "A", page = 0, cellX = 0, cellY = 0),
            HomeItemEntity(
                id = 2, page = 0, cellX = 1, cellY = 0, spanX = 3, spanY = 2,
                appWidgetId = 7, widgetProvider = "com.w/com.w.Prov",
            ),
        )
        val items = BackupMapper.toBackupItems(entities, 0L)
        assertThat(items.map { it.id }).containsExactly(1L, 2L)
        val widget = items.first { it.id == 2L }
        assertThat(widget.widgetProvider).isEqualTo("com.w/com.w.Prov")
        assertThat(widget.spanX).isEqualTo(3)
        assertThat(widget.spanY).isEqualTo(2)
        // The device-local appWidgetId is never carried into the backup.
    }

    @Test
    fun toBackupItems_marks_mainProfile_by_the_current_user_serial() {
        val entities = listOf(
            HomeItemEntity(id = 1, packageName = "com.a", className = "A", userSerial = 10L, page = 0, cellX = 0, cellY = 0),
            HomeItemEntity(id = 2, packageName = "com.b", className = "B", userSerial = 0L, page = 0, cellX = 1, cellY = 0),
        )
        val items = BackupMapper.toBackupItems(entities, mainUserSerial = 10L)
        assertThat(items.first { it.id == 1L }.mainProfile).isTrue()   // current user IS the main profile
        assertThat(items.first { it.id == 2L }.mainProfile).isFalse()  // some other profile
    }

    @Test
    fun toEntities_keeps_widget_when_provider_installed_unbound() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 2, spanY = 2, widgetProvider = "com.w/com.w.Prov"),
            BackupItem(2, -1, null, "", "", true, null, 0, 2, 0, spanX = 1, spanY = 1, widgetProvider = "com.gone/com.gone.Prov"),
        )
        val mapping = toEntities(items, installedPackages = setOf("com.w"))
        assertThat(mapping.skipped).isEqualTo(1) // com.gone provider not installed
        val widget = mapping.entities.single()
        assertThat(widget.id).isEqualTo(1L)
        assertThat(widget.widgetProvider).isEqualTo("com.w/com.w.Prov")
        assertThat(widget.spanX).isEqualTo(2)
        assertThat(widget.spanY).isEqualTo(2)
        assertThat(widget.appWidgetId).isNull() // unbound until re-bound on this device
    }

    @Test
    fun toEntities_keeps_widget_from_a_widget_only_package() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 2, spanY = 1, widgetProvider = "com.widgetonly/P"),
        )
        val mapping = toEntities(items, widgetPackages = setOf("com.widgetonly"))
        assertThat(mapping.entities.map { it.id }).containsExactly(1L)
        assertThat(mapping.skipped).isEqualTo(0)
    }

    @Test
    fun toEntities_skips_uninstalled_and_remaps_serial() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 0, 0),   // installed
            BackupItem(2, -1, null, "com.gone", "G", true, null, 0, 1, 0), // not installed -> skipped
            BackupItem(3, -1, "Tools", "", "", false, null, 0, 2, 0),      // folder -> always kept
        )
        val mapping = toEntities(items, installedAppKeys = setOf("com.a/A"), installedPackages = setOf("com.a"))
        assertThat(mapping.skipped).isEqualTo(1)
        assertThat(mapping.entities.map { it.id }).containsExactly(1L, 3L)
        assertThat(mapping.entities.first { it.id == 1L }.userSerial).isEqualTo(42L)
        assertThat(mapping.entities.first { it.id == 3L }.userSerial).isEqualTo(0L) // folder
        assertThat(mapping.entities.all { it.appWidgetId == null }).isTrue()
    }

    @Test
    fun toBackupItems_includes_builtin_type() {
        val entities = listOf(
            HomeItemEntity(id = 1, page = 0, cellX = 0, cellY = 0, spanX = 4, spanY = 2, builtinType = "smartspace"),
        )
        val item = BackupMapper.toBackupItems(entities, 0L).single()
        assertThat(item.builtinType).isEqualTo("smartspace")
        assertThat(item.spanX).isEqualTo(4)
        assertThat(item.spanY).isEqualTo(2)
        assertThat(item.widgetProvider).isNull()
    }

    @Test
    fun toEntities_keeps_builtin_rows_without_any_install_check() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 4, spanY = 2, builtinType = "smartspace"),
        )
        val mapping = toEntities(items)
        assertThat(mapping.skipped).isEqualTo(0)
        val e = mapping.entities.single()
        assertThat(e.builtinType).isEqualTo("smartspace")
        assertThat(e.spanX).isEqualTo(4)
        assertThat(e.spanY).isEqualTo(2)
        assertThat(e.appWidgetId).isNull()
        assertThat(e.widgetProvider).isNull()
        assertThat(e.userSerial).isEqualTo(0L) // built-ins have no profile
    }

    @Test
    fun toEntities_keeps_a_notifications_builtin_row() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 4, spanY = 1, builtinType = "notifications"),
        )
        val mapping = toEntities(items)
        assertThat(mapping.skipped).isEqualTo(0)
        val e = mapping.entities.single()
        assertThat(e.builtinType).isEqualTo("notifications")
        assertThat(e.spanX).isEqualTo(4)
        assertThat(e.spanY).isEqualTo(1)
        assertThat(e.appWidgetId).isNull()
    }

    @Test
    fun toEntities_clamps_builtin_spans_like_a_widget_footprint() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 30, spanY = -2, builtinType = "smartspace"),
        )
        val e = toEntities(items, columns = SettingsRepository.MAX_COLUMNS).entities.single()
        assertThat(e.spanX).isEqualTo(SettingsRepository.MAX_COLUMNS)
        assertThat(e.spanY).isEqualTo(1)
    }

    // --- Corrupt/hand-edited backup sanitization ---------------------------------------------

    @Test
    fun toEntities_skips_rows_with_invalid_page_or_cells() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 999_999_999, 0, 0), // insane page
            BackupItem(2, -1, null, "com.a", "A", true, null, -1, 0, 0),          // negative page
            BackupItem(3, -1, null, "com.a", "A", true, null, 0, -2, 0),          // negative cellX
            BackupItem(4, -1, null, "com.a", "A", true, null, 0, 0, -7),          // negative cellY
            BackupItem(5, -1, null, "com.a", "A", true, null, 0, 1, 1),           // valid
        )
        val mapping = toEntities(items, installedAppKeys = setOf("com.a/A"), installedPackages = setOf("com.a"))
        assertThat(mapping.entities.map { it.id }).containsExactly(5L)
        assertThat(mapping.skipped).isEqualTo(4)
    }

    @Test
    fun toEntities_keeps_a_battery_builtin_row() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 1, spanY = 1, builtinType = "battery"),
        )
        val mapping = toEntities(items)
        assertThat(mapping.skipped).isEqualTo(0)
        val e = mapping.entities.single()
        assertThat(e.builtinType).isEqualTo("battery")
        assertThat(e.spanX).isEqualTo(1)
    }

    @Test
    fun toEntities_validates_against_the_backups_own_row_count() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 0, 7), // row 7 — only on an 8-row grid
        )
        val eightRows = BackupMapper.toEntities(
            items, 42L, setOf("com.a/A"), setOf("com.a"), emptySet(), columns = 4, gridRows = 8,
        )
        assertThat(eightRows.entities.map { it.id }).containsExactly(1L)

        val sixRows = toEntities(items, installedAppKeys = setOf("com.a/A"), installedPackages = setOf("com.a"))
        assertThat(sixRows.entities).isEmpty()
        assertThat(sixRows.skipped).isEqualTo(1)
    }

    @Test
    fun toEntities_keeps_an_item_on_the_trailing_page_index_MAX_PAGES() {
        // The pager offers one page past the cap while dragging, so index MAX_PAGES is a legal
        // home for an item — restore must keep it (and still reject anything beyond).
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, HomeLayoutRepository.MAX_PAGES, 0, 0),
            BackupItem(2, -1, null, "com.a", "A", true, null, HomeLayoutRepository.MAX_PAGES + 1, 1, 0),
        )
        val mapping = toEntities(items, installedAppKeys = setOf("com.a/A"), installedPackages = setOf("com.a"))
        assertThat(mapping.entities.map { it.id }).containsExactly(1L)
        assertThat(mapping.skipped).isEqualTo(1)
    }

    @Test
    fun toEntities_rejects_home_cell_outside_the_real_grid() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 10, 0),  // cellX beyond 4 columns
            BackupItem(2, -1, null, "com.a", "A", true, null, 0, 0, 999), // cellY beyond 6 rows
        )
        val mapping = toEntities(items, installedAppKeys = setOf("com.a/A"), installedPackages = setOf("com.a"))
        assertThat(mapping.entities).isEmpty()
        assertThat(mapping.skipped).isEqualTo(2)
    }

    @Test
    fun toEntities_clamps_widget_spans_to_the_grid() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 30, spanY = -2, widgetProvider = "com.w/P"),
        )
        val mapping = toEntities(items, installedPackages = setOf("com.w"), columns = SettingsRepository.MAX_COLUMNS)
        val w = mapping.entities.single()
        assertThat(w.spanX).isEqualTo(SettingsRepository.MAX_COLUMNS)
        assertThat(w.spanY).isEqualTo(1) // negative -> 1
    }

    @Test
    fun toEntities_rejects_widget_past_right_or_bottom_edge() {
        val items = listOf(
            // cellX 3 + span 2 = 5 > 4 columns -> rejected (position, not span, is at fault)
            BackupItem(1, -1, null, "", "", true, null, 0, 3, 0, spanX = 2, spanY = 1, widgetProvider = "com.w/P"),
            // cellY 5 + span 2 = 7 > 6 rows -> rejected
            BackupItem(2, -1, null, "", "", true, null, 0, 0, 5, spanX = 1, spanY = 2, widgetProvider = "com.w/P"),
            // oversized span at origin is CLAMPED to the grid, not rejected
            BackupItem(3, -1, null, "", "", true, null, 1, 0, 0, spanX = 9, spanY = 9, widgetProvider = "com.w/P"),
        )
        val mapping = toEntities(items, installedPackages = setOf("com.w"))
        assertThat(mapping.skipped).isEqualTo(2)
        val clamped = mapping.entities.single()
        assertThat(clamped.spanX).isEqualTo(4)
        assertThat(clamped.spanY).isEqualTo(6)
    }

    @Test
    fun toEntities_rejects_icon_inside_widget_footprint() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 2, spanY = 2, widgetProvider = "com.w/P"),
            BackupItem(2, -1, null, "com.a", "A", true, null, 0, 1, 0), // start cell inside the 2x2
            BackupItem(3, -1, null, "com.a", "A", true, null, 0, 2, 0), // outside -> kept
        )
        val mapping = toEntities(items, installedAppKeys = setOf("com.a/A"), installedPackages = setOf("com.w", "com.a"))
        assertThat(mapping.entities.map { it.id }).containsExactly(1L, 3L)
        assertThat(mapping.skipped).isEqualTo(1)
    }

    @Test
    fun toEntities_rejects_overlapping_widget_footprints() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 3, spanY = 2, widgetProvider = "com.w/P"),
            BackupItem(2, -1, null, "", "", true, null, 0, 2, 1, spanX = 2, spanY = 2, widgetProvider = "com.w/P"),
        )
        val mapping = toEntities(items, installedPackages = setOf("com.w"))
        assertThat(mapping.entities.map { it.id }).containsExactly(1L)
        assertThat(mapping.skipped).isEqualTo(1)
    }

    @Test
    fun toEntities_skips_orphan_folder_children() {
        val items = listOf(
            BackupItem(10, -1, "Tools", "", "", true, null, 0, 0, 0),      // folder kept
            BackupItem(11, 10, null, "com.a", "A", true, null, 0, 0, 0),   // child of kept folder
            BackupItem(12, 99, null, "com.a", "A", true, null, 0, 0, 0),   // orphan -> skipped
        )
        val mapping = toEntities(items, installedAppKeys = setOf("com.a/A"), installedPackages = setOf("com.a"))
        assertThat(mapping.entities.map { it.id }).containsExactly(10L, 11L)
        assertThat(mapping.skipped).isEqualTo(1)
    }

    @Test
    fun toEntities_skips_duplicate_app_in_the_same_container_but_allows_it_in_another() {
        val items = listOf(
            BackupItem(10, -1, "Tools", "", "", true, null, 0, 0, 0),
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 0, 1),
            BackupItem(2, -1, null, "com.a", "A", true, null, 0, 1, 1),   // dup on HOME -> skipped
            BackupItem(3, 10, null, "com.a", "A", true, null, 0, 0, 0),   // same app in folder -> OK
        )
        val mapping = toEntities(items, installedAppKeys = setOf("com.a/A"), installedPackages = setOf("com.a"))
        assertThat(mapping.entities.map { it.id }).containsExactly(10L, 1L, 3L)
        assertThat(mapping.skipped).isEqualTo(1)
    }

    @Test
    fun toEntities_rejects_unknown_builtin_and_malformed_widget_provider() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, builtinType = "evil"),
            BackupItem(2, -1, null, "", "", true, null, 0, 1, 0, widgetProvider = "no-slash"),
            BackupItem(3, -1, null, "", "", true, null, 0, 2, 0, spanX = 2, builtinType = "notifications"),
        )
        val mapping = toEntities(items, installedPackages = setOf("no-slash"))
        assertThat(mapping.entities.map { it.id }).containsExactly(3L)
        assertThat(mapping.skipped).isEqualTo(2)
    }

    @Test
    fun toEntities_drops_duplicate_cells_keeping_the_first() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 0, 0),
            BackupItem(2, -1, null, "com.b", "B", true, null, 0, 0, 0), // same cell -> dropped
            BackupItem(3, -1, null, "com.b", "B", true, null, 0, 1, 0),
        )
        val mapping = toEntities(
            items,
            installedAppKeys = setOf("com.a/A", "com.b/B"),
            installedPackages = setOf("com.a", "com.b"),
        )
        assertThat(mapping.entities.map { it.id }).containsExactly(1L, 3L)
        assertThat(mapping.skipped).isEqualTo(1)
    }

    @Test
    fun toEntities_drops_duplicate_ids_keeping_the_first() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 0, 0),
            BackupItem(1, -1, null, "com.b", "B", true, null, 0, 1, 0), // same id -> dropped
        )
        val mapping = toEntities(
            items,
            installedAppKeys = setOf("com.a/A", "com.b/B"),
            installedPackages = setOf("com.a", "com.b"),
        )
        assertThat(mapping.entities).hasSize(1)
        assertThat(mapping.entities.single().packageName).isEqualTo("com.a")
        assertThat(mapping.skipped).isEqualTo(1)
    }
}
