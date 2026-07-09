package org.arkikeskus.launcher.data.backup

import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
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
                builtinType = e.builtinType,
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
        val seenIds = HashSet<Long>()
        val seenCells = HashSet<List<Long>>()
        for (it in items) {
            val keep = when {
                // Built-in widget: nothing to install or bind — always restorable.
                it.builtinType != null -> true
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
            // The backup file is plaintext JSON the user can edit (and Drive/storage can corrupt), so
            // grid values must be sanitized before they reach the DB: an insane page would hang the
            // pager/page-dots UI, and a negative span crashes Compose layout the moment it renders.
            val sane = it.page in 0 until HomeLayoutRepository.MAX_PAGES &&
                it.cellX in 0..MAX_CELL && it.cellY in 0..MAX_CELL
            if (!sane) { skipped++; continue }
            // Duplicate ids/cells would violate the DB's primary key / unique cell index and roll
            // back the whole restore — drop the later duplicates instead, keeping the first.
            if (!seenIds.add(it.id)) { skipped++; continue }
            if (!seenCells.add(listOf(it.containerId, it.page.toLong(), it.cellX.toLong(), it.cellY.toLong()))) {
                skipped++; continue
            }
            val footprint = it.widgetProvider != null || it.builtinType != null
            kept.add(
                HomeItemEntity(
                    id = it.id,
                    containerId = it.containerId,
                    folderName = it.folderName,
                    packageName = it.packageName,
                    className = it.className,
                    userSerial = if (it.folderName != null || it.builtinType != null) 0L else mainUserSerial,
                    shortcutId = it.shortcutId,
                    page = it.page,
                    cellX = it.cellX,
                    cellY = it.cellY,
                    // Only widgets (app or built-in) have a real footprint; clamp it to the grid (the
                    // normal add path does the same). Everything else is 1×1 whatever the file says.
                    spanX = if (footprint) it.spanX.coerceIn(1, SettingsRepository.MAX_COLUMNS) else 1,
                    spanY = if (footprint) it.spanY.coerceIn(1, HomeLayoutRepository.ROWS) else 1,
                    // Restored widgets come back unbound (no device-local id yet); the home screen
                    // re-binds each via a tap-to-set-up placeholder. Non-widget rows keep provider null.
                    appWidgetId = null,
                    widgetProvider = it.widgetProvider,
                    builtinType = it.builtinType,
                ),
            )
        }
        return RestoreMapping(kept, skipped)
    }

    /** Backups only record main-profile membership; the original serial is device-local. */
    private const val MAIN_SERIAL = 0L

    /** Upper bound for restored cell coordinates (folder children index by cellX, so it's roomy). */
    private const val MAX_CELL = 999
}
