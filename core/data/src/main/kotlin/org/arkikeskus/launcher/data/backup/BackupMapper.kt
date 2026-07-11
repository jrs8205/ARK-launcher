package org.arkikeskus.launcher.data.backup

import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity

data class RestoreMapping(val entities: List<HomeItemEntity>, val skipped: Int)

object BackupMapper {

    fun toBackupItems(entities: List<HomeItemEntity>, mainUserSerial: Long): List<BackupItem> =
        entities.map { e ->
            BackupItem(
                id = e.id,
                containerId = e.containerId,
                folderName = e.folderName,
                packageName = e.packageName,
                className = e.className,
                // The serial is device-local; the backup records only "belongs to the profile the
                // launcher runs as". Comparing against a literal 0 broke every secondary-user
                // install (all items exported as non-main and dropped on restore).
                mainProfile = e.userSerial == mainUserSerial,
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

    /**
     * Maps backup items to DB rows, dropping anything that cannot restore safely. The backup file
     * is plaintext JSON the user can edit (and Drive/storage can corrupt), so beyond "is the app
     * installed" this validates the full item graph: real grid bounds for [columns]×ROWS, whole
     * spanX×spanY footprints (a start-cell-only check let a restored widget cover other items),
     * folder children against surviving folder rows, known builtin types, well-formed widget
     * providers, and one instance per app per container. [widgetPackages] covers widget-only apps
     * that have no launcher activity and so are absent from [installedPackages].
     */
    fun toEntities(
        items: List<BackupItem>,
        mainUserSerial: Long,
        installedAppKeys: Set<String>,
        installedPackages: Set<String>,
        widgetPackages: Set<String>,
        columns: Int,
    ): RestoreMapping {
        val cols = columns.coerceIn(SettingsRepository.MIN_COLUMNS, SettingsRepository.MAX_COLUMNS)
        val rows = HomeLayoutRepository.ROWS
        val kept = ArrayList<HomeItemEntity>()
        var skipped = 0
        val seenIds = HashSet<Long>()
        val seenAppKeys = HashSet<String>()
        val occupied = HashSet<Triple<Int, Int, Int>>()
        val keptFolderIds = HashSet<Long>()

        val (homeItems, childItems) = items.partition { it.containerId == HomeItemEntity.HOME }

        for (it in homeItems) {
            val keep = when {
                it.builtinType != null -> it.builtinType in KNOWN_BUILTINS
                it.widgetProvider != null -> {
                    val pkg = it.widgetProvider.substringBefore('/')
                    val cls = it.widgetProvider.substringAfter('/', "")
                    pkg.isNotBlank() && cls.isNotBlank() &&
                        (pkg in installedPackages || pkg in widgetPackages)
                }
                it.folderName != null -> true
                !it.mainProfile -> false                                        // v1: main profile only
                it.shortcutId != null -> it.packageName in installedPackages    // pinned shortcut
                else -> "${it.packageName}/${it.className}" in installedAppKeys // app
            }
            if (!keep) { skipped++; continue }
            if (it.id in seenIds) { skipped++; continue }
            val plainApp = it.builtinType == null && it.widgetProvider == null &&
                it.folderName == null && it.shortcutId == null
            val appKey = "${it.containerId}/${it.packageName}/${it.className}"
            if (plainApp && appKey in seenAppKeys) { skipped++; continue }
            // Only widgets (app or built-in) have a real footprint; an oversized span shrinks to
            // the grid (the normal add path does the same) but a bad POSITION is rejected below.
            val footprint = it.widgetProvider != null || it.builtinType != null
            val spanX = if (footprint) it.spanX.coerceIn(1, cols) else 1
            val spanY = if (footprint) it.spanY.coerceIn(1, rows) else 1
            val inGrid = it.page in 0..HomeLayoutRepository.MAX_PAGES &&
                it.cellX >= 0 && it.cellY >= 0 &&
                it.cellX + spanX <= cols && it.cellY + spanY <= rows
            if (!inGrid) { skipped++; continue }
            val cells = ArrayList<Triple<Int, Int, Int>>(spanX * spanY)
            for (x in it.cellX until it.cellX + spanX) {
                for (y in it.cellY until it.cellY + spanY) cells.add(Triple(it.page, x, y))
            }
            if (cells.any { c -> c in occupied }) { skipped++; continue }
            // Claim ids/keys/cells only for a KEPT item — a rejected item must not block a later
            // valid one from restoring under the same app key or id.
            seenIds.add(it.id)
            if (plainApp) seenAppKeys.add(appKey)
            occupied.addAll(cells)
            if (it.folderName != null) keptFolderIds.add(it.id)
            kept.add(entity(it, mainUserSerial, spanX, spanY))
        }

        // Folder children: only rows whose parent folder survived (an orphan is an invisible row
        // that still blocks cells); cellX is the in-folder order index, not a grid coordinate.
        val seenChildCells = HashSet<List<Long>>()
        for (it in childItems) {
            val keep = when {
                it.containerId !in keptFolderIds -> false
                it.builtinType != null || it.widgetProvider != null || it.folderName != null -> false
                !it.mainProfile -> false
                it.shortcutId != null -> it.packageName in installedPackages
                else -> "${it.packageName}/${it.className}" in installedAppKeys
            }
            if (!keep) { skipped++; continue }
            if (it.id in seenIds) { skipped++; continue }
            val appKey = "${it.containerId}/${it.packageName}/${it.className}"
            if (appKey in seenAppKeys) { skipped++; continue }
            val sane = it.page in 0..HomeLayoutRepository.MAX_PAGES &&
                it.cellX in 0..MAX_CHILD_INDEX && it.cellY in 0..MAX_CHILD_INDEX
            if (!sane) { skipped++; continue }
            if (!seenChildCells.add(listOf(it.containerId, it.page.toLong(), it.cellX.toLong(), it.cellY.toLong()))) {
                skipped++; continue
            }
            seenIds.add(it.id)
            seenAppKeys.add(appKey)
            kept.add(entity(it, mainUserSerial, 1, 1))
        }
        return RestoreMapping(kept, skipped)
    }

    private fun entity(it: BackupItem, mainUserSerial: Long, spanX: Int, spanY: Int) = HomeItemEntity(
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
        spanX = spanX,
        spanY = spanY,
        // Restored widgets come back unbound (no device-local id yet); the home screen re-binds
        // each via a tap-to-set-up placeholder. Non-widget rows keep provider null.
        appWidgetId = null,
        widgetProvider = it.widgetProvider,
        builtinType = it.builtinType,
    )

    /** The builtin types this version can render — anything else in a file must not restore. */
    private val KNOWN_BUILTINS = setOf(HomeItemEntity.BUILTIN_SMARTSPACE, HomeItemEntity.BUILTIN_NOTIFICATIONS)

    /** Upper bound for folder-child order indices (roomy; children index by cellX). */
    private const val MAX_CHILD_INDEX = 999
}
