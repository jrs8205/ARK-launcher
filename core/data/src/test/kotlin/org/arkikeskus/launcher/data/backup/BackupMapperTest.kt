package org.arkikeskus.launcher.data.backup

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.Test

class BackupMapperTest {

    @Test
    fun toBackupItems_includes_widgets_with_span_and_provider() {
        val entities = listOf(
            HomeItemEntity(id = 1, packageName = "com.a", className = "A", page = 0, cellX = 0, cellY = 0),
            HomeItemEntity(
                id = 2, page = 0, cellX = 1, cellY = 0, spanX = 3, spanY = 2,
                appWidgetId = 7, widgetProvider = "com.w/com.w.Prov",
            ),
        )
        val items = BackupMapper.toBackupItems(entities)
        assertThat(items.map { it.id }).containsExactly(1L, 2L)
        val widget = items.first { it.id == 2L }
        assertThat(widget.widgetProvider).isEqualTo("com.w/com.w.Prov")
        assertThat(widget.spanX).isEqualTo(3)
        assertThat(widget.spanY).isEqualTo(2)
        // The device-local appWidgetId is never carried into the backup.
    }

    @Test
    fun toEntities_keeps_widget_when_provider_installed_unbound() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 2, spanY = 2, widgetProvider = "com.w/com.w.Prov"),
            BackupItem(2, -1, null, "", "", true, null, 0, 2, 0, spanX = 1, spanY = 1, widgetProvider = "com.gone/com.gone.Prov"),
        )
        val mapping = BackupMapper.toEntities(
            items = items,
            mainUserSerial = 42L,
            installedAppKeys = emptySet(),
            installedPackages = setOf("com.w"),
        )
        assertThat(mapping.skipped).isEqualTo(1) // com.gone provider not installed
        val widget = mapping.entities.single()
        assertThat(widget.id).isEqualTo(1L)
        assertThat(widget.widgetProvider).isEqualTo("com.w/com.w.Prov")
        assertThat(widget.spanX).isEqualTo(2)
        assertThat(widget.spanY).isEqualTo(2)
        assertThat(widget.appWidgetId).isNull() // unbound until re-bound on this device
    }

    @Test
    fun toEntities_skips_uninstalled_and_remaps_serial() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 0, 0),   // installed
            BackupItem(2, -1, null, "com.gone", "G", true, null, 0, 1, 0), // not installed -> skipped
            BackupItem(3, -1, "Tools", "", "", false, null, 0, 2, 0),      // folder -> always kept
        )
        val mapping = BackupMapper.toEntities(
            items = items,
            mainUserSerial = 42L,
            installedAppKeys = setOf("com.a/A"),
            installedPackages = setOf("com.a"),
        )
        assertThat(mapping.skipped).isEqualTo(1)
        assertThat(mapping.entities.map { it.id }).containsExactly(1L, 3L)
        assertThat(mapping.entities.first { it.id == 1L }.userSerial).isEqualTo(42L)
        assertThat(mapping.entities.first { it.id == 3L }.userSerial).isEqualTo(0L) // folder
        assertThat(mapping.entities.all { it.appWidgetId == null }).isTrue()
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
        val mapping = BackupMapper.toEntities(items, 0L, setOf("com.a/A"), setOf("com.a"))
        assertThat(mapping.entities.map { it.id }).containsExactly(5L)
        assertThat(mapping.skipped).isEqualTo(4)
    }

    @Test
    fun toEntities_clamps_widget_spans_to_the_grid() {
        val items = listOf(
            BackupItem(1, -1, null, "", "", true, null, 0, 0, 0, spanX = 30, spanY = -2, widgetProvider = "com.w/P"),
        )
        val mapping = BackupMapper.toEntities(items, 0L, emptySet(), setOf("com.w"))
        val w = mapping.entities.single()
        assertThat(w.spanX).isEqualTo(SettingsRepository.MAX_COLUMNS)
        assertThat(w.spanY).isEqualTo(1) // negative -> 1
    }

    @Test
    fun toEntities_forces_non_widget_spans_to_1x1() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 0, 0, spanX = 5, spanY = 3),
        )
        val mapping = BackupMapper.toEntities(items, 0L, setOf("com.a/A"), setOf("com.a"))
        val e = mapping.entities.single()
        assertThat(e.spanX).isEqualTo(1)
        assertThat(e.spanY).isEqualTo(1)
    }

    @Test
    fun toEntities_drops_duplicate_cells_keeping_the_first() {
        val items = listOf(
            BackupItem(1, -1, null, "com.a", "A", true, null, 0, 0, 0),
            BackupItem(2, -1, null, "com.b", "B", true, null, 0, 0, 0), // same cell -> dropped
            BackupItem(3, -1, null, "com.b", "B", true, null, 0, 1, 0),
        )
        val mapping = BackupMapper.toEntities(
            items, 0L, setOf("com.a/A", "com.b/B"), setOf("com.a", "com.b"),
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
        val mapping = BackupMapper.toEntities(
            items, 0L, setOf("com.a/A", "com.b/B"), setOf("com.a", "com.b"),
        )
        assertThat(mapping.entities).hasSize(1)
        assertThat(mapping.entities.single().packageName).isEqualTo("com.a")
        assertThat(mapping.skipped).isEqualTo(1)
    }
}
