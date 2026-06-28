package org.arkikeskus.launcher.data.backup

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.Test

class BackupMapperTest {

    @Test
    fun toBackupItems_excludes_widgets() {
        val entities = listOf(
            HomeItemEntity(id = 1, packageName = "com.a", className = "A", page = 0, cellX = 0, cellY = 0),
            HomeItemEntity(id = 2, page = 0, cellX = 1, cellY = 0, appWidgetId = 7, widgetProvider = "p/c"),
        )
        val items = BackupMapper.toBackupItems(entities)
        assertThat(items.map { it.id }).containsExactly(1L)
        assertThat(items.first().mainProfile).isTrue()
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
}
