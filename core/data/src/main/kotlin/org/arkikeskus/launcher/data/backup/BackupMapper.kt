package org.arkikeskus.launcher.data.backup

import org.arkikeskus.launcher.data.local.HomeItemEntity

data class RestoreMapping(val entities: List<HomeItemEntity>, val skipped: Int)

object BackupMapper {

    fun toBackupItems(entities: List<HomeItemEntity>): List<BackupItem> =
        entities.filterNot { it.isWidget }.map { e ->
            BackupItem(
                id = e.id,
                containerId = e.containerId,
                folderName = e.folderName,
                packageName = e.packageName,
                className = e.className,
                mainProfile = e.userSerial == MAIN_SERIAL,
                shortcutId = e.shortcutId,
                page = e.page,
                cellX = e.cellX,
                cellY = e.cellY,
            )
        }

    fun toEntities(
        items: List<BackupItem>,
        mainUserSerial: Long,
        installedAppKeys: Set<String>,
        installedPackages: Set<String>,
    ): RestoreMapping {
        val kept = ArrayList<HomeItemEntity>()
        var skipped = 0
        for (it in items) {
            val keep = when {
                it.folderName != null -> true                                  // folder row
                !it.mainProfile -> false                                       // v1: main profile only
                it.shortcutId != null -> it.packageName in installedPackages   // pinned shortcut
                else -> "${it.packageName}/${it.className}" in installedAppKeys // app
            }
            if (!keep) { skipped++; continue }
            kept.add(
                HomeItemEntity(
                    id = it.id,
                    containerId = it.containerId,
                    folderName = it.folderName,
                    packageName = it.packageName,
                    className = it.className,
                    userSerial = if (it.folderName != null) 0L else mainUserSerial,
                    shortcutId = it.shortcutId,
                    page = it.page,
                    cellX = it.cellX,
                    cellY = it.cellY,
                    spanX = 1,
                    spanY = 1,
                    appWidgetId = null,
                    widgetProvider = null,
                ),
            )
        }
        return RestoreMapping(kept, skipped)
    }

    /** Backups only record main-profile membership; the original serial is device-local. */
    private const val MAIN_SERIAL = 0L
}
