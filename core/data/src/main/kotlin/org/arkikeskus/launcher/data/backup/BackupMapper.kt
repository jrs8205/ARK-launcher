package org.arkikeskus.launcher.data.backup

import org.arkikeskus.launcher.data.local.HomeItemEntity

data class RestoreMapping(val entities: List<HomeItemEntity>, val skipped: Int)

object BackupMapper {

    fun toBackupItems(entities: List<HomeItemEntity>): List<BackupItem> =
        entities.map { e ->
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
                // Widgets record their size + provider; the device-local appWidgetId is NOT backed up.
                spanX = e.spanX,
                spanY = e.spanY,
                widgetProvider = e.widgetProvider,
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
                // Widget: keep only if the provider's app is installed; it is re-bound on this device
                // (appWidgetId stays null until then). The package is the part before '/' in the
                // flattened provider ComponentName — parsed by hand to keep this pure-JVM testable.
                it.widgetProvider != null -> it.widgetProvider.substringBefore('/') in installedPackages
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
                    spanX = it.spanX,
                    spanY = it.spanY,
                    // Restored widgets come back unbound (no device-local id yet); the home screen
                    // re-binds each via a tap-to-set-up placeholder. Non-widget rows keep provider null.
                    appWidgetId = null,
                    widgetProvider = it.widgetProvider,
                ),
            )
        }
        return RestoreMapping(kept, skipped)
    }

    /** Backups only record main-profile membership; the original serial is device-local. */
    private const val MAIN_SERIAL = 0L
}
