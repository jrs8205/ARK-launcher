package org.arkikeskus.launcher.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

/** The shortcut ids still pinned for a (package, user) after a removal — used to re-pin the set. */
data class RemainingPins(val packageName: String, val userSerial: Long, val shortcutIds: List<String>)

/**
 * Persists the home layout (Room): app shortcuts and folders placed at free cells, and the apps
 * inside each folder. Top-level items live in the [HOME] container; a folder's children live in the
 * container identified by the folder row's id. Each container is an independent cell space guarded by
 * the unique (containerId, page, cellX, cellY) index, so the swaps/repacks below stay collision-free.
 */
@Singleton
class HomeLayoutRepository @Inject constructor(
    private val db: LauncherDatabase,
    private val dao: HomeItemDao,
) {
    /** All rows (home items, folders, folder children); the ViewModel partitions by container. */
    val homeItems: Flow<List<HomeItemEntity>> = dao.observeAll()

    suspend fun addToHome(appItem: AppItem, columns: Int) {
        // Atomic count-then-insert: without a transaction a fast double call could read "absent" twice
        // and place the same app into two different cells.
        db.withTransaction {
            if (dao.count(HOME, appItem.packageName, appItem.className, appItem.userSerial) > 0) {
                return@withTransaction
            }
            val (page, cellX, cellY) = firstFreeCell(dao.getContainer(HOME), columns)
            dao.insert(homeApp(appItem, HOME, page, cellX, cellY))
        }
    }

    /** Moves/swaps a home item to a target home cell. Returns false if the item is gone. */
    suspend fun moveItem(appItem: AppItem, page: Int, cellX: Int, cellY: Int): Boolean =
        db.withTransaction {
            val source = dao.getByKey(HOME, appItem.packageName, appItem.className, appItem.userSerial)
                ?: return@withTransaction false
            moveOrSwap(source, page, cellX, cellY)
            true
        }

    /** Places [appItem] on the home screen at a cell (used for a dock→home drop), swapping/falling back. */
    suspend fun placeAt(appItem: AppItem, page: Int, cellX: Int, cellY: Int, columns: Int): Boolean =
        db.withTransaction {
            val existing = dao.getByKey(HOME, appItem.packageName, appItem.className, appItem.userSerial)
            if (existing != null) {
                moveOrSwap(existing, page, cellX, cellY)
                return@withTransaction true
            }
            val items = dao.getContainer(HOME)
            val (p, x, y) = if (dao.getAt(HOME, page, cellX, cellY) == null &&
                !cellInWidget(items, -1L, page, cellX, cellY)
            ) {
                Triple(page, cellX, cellY)
            } else {
                firstFreeCell(items, columns)
            }
            dao.insert(homeApp(appItem, HOME, p, x, y))
            true
        }

    suspend fun removeFromHome(appItem: AppItem) {
        dao.deleteByKey(HOME, appItem.packageName, appItem.className, appItem.userSerial)
    }

    /** Moves/swaps a folder (or any home row, by id) to a target home cell. */
    suspend fun moveFolder(folderId: Long, page: Int, cellX: Int, cellY: Int): Boolean =
        db.withTransaction {
            val source = dao.getById(folderId) ?: return@withTransaction false
            moveOrSwap(source, page, cellX, cellY)
            true
        }

    /** Pins a deep shortcut at the first free home cell (no-op if it's already on home). */
    suspend fun addShortcut(packageName: String, shortcutId: String, userSerial: Long, columns: Int) {
        db.withTransaction {
            val present = dao.getContainer(HOME).any {
                it.shortcutId == shortcutId && it.packageName == packageName && it.userSerial == userSerial
            }
            if (present) return@withTransaction
            val (page, x, y) = firstFreeCell(dao.getContainer(HOME), columns)
            dao.insert(
                HomeItemEntity(
                    containerId = HOME,
                    packageName = packageName,
                    userSerial = userSerial,
                    shortcutId = shortcutId,
                    page = page,
                    cellX = x,
                    cellY = y,
                ),
            )
        }
    }

    /** Removes a pinned-shortcut row; returns the ids still pinned for its (package, user) so the
     *  caller can re-pin the remaining set in the system (pinShortcuts replaces the whole set). */
    suspend fun removeShortcut(rowId: Long): RemainingPins? = db.withTransaction {
        val row = dao.getById(rowId) ?: return@withTransaction null
        if (!row.isShortcut) return@withTransaction null
        dao.deleteById(rowId)
        val remaining = dao.getContainer(HOME)
            .filter { it.shortcutId != null && it.packageName == row.packageName && it.userSerial == row.userSerial }
            .mapNotNull { it.shortcutId }
        RemainingPins(row.packageName, row.userSerial, remaining)
    }

    /**
     * Repacks the top-level home items (apps and folders) into a [columns]-wide grid, preserving
     * reading order and spilling overflow onto later pages. Folder *contents* are left untouched.
     */
    suspend fun reflow(columns: Int) {
        if (columns <= 0) return
        db.withTransaction {
            val rows = dao.getContainerOrdered(HOME)
            if (rows.isEmpty()) return@withTransaction
            // Park everything off-grid first (unique temp cells) so the repack can't collide.
            rows.forEachIndexed { i, row -> dao.moveById(row.id, HOME, -1, -(i + 1), -1) }
            val slotsPerPage = columns * ROWS
            rows.forEachIndexed { i, row ->
                val slot = i % slotsPerPage
                dao.moveById(row.id, HOME, i / slotsPerPage, slot % columns, slot / columns)
            }
        }
    }

    // --- Folders ---------------------------------------------------------------------------------

    /**
     * Creates a folder at [target]'s home cell containing [target] and [dropped] (dropped onto it).
     * Returns the new folder id, or -1 if either app is no longer on the home screen.
     */
    suspend fun createFolder(target: AppItem, dropped: AppItem, name: String): Long =
        db.withTransaction {
            val targetRow = dao.getByKey(HOME, target.packageName, target.className, target.userSerial)
                ?: return@withTransaction -1L
            val droppedRow = dao.getByKey(HOME, dropped.packageName, dropped.className, dropped.userSerial)
                ?: return@withTransaction -1L
            if (targetRow.id == droppedRow.id) return@withTransaction -1L
            val page = targetRow.page
            val cellX = targetRow.cellX
            val cellY = targetRow.cellY
            // Folder starts off-grid so it doesn't clash with the target cell it's about to take over.
            val folderId = dao.insert(
                HomeItemEntity(containerId = HOME, folderName = name, page = -1, cellX = -1, cellY = -1),
            )
            dao.moveById(targetRow.id, folderId, 0, 0, 0)
            dao.moveById(droppedRow.id, folderId, 0, 1, 0)
            dao.moveById(folderId, HOME, page, cellX, cellY)
            folderId
        }

    /** Moves [appItem] off the home screen into [folderId], appended after its current children. */
    suspend fun addToFolder(appItem: AppItem, folderId: Long) {
        db.withTransaction {
            val row = dao.getByKey(HOME, appItem.packageName, appItem.className, appItem.userSerial)
                ?: return@withTransaction
            val order = dao.childCount(folderId)
            dao.moveById(row.id, folderId, 0, order, 0)
        }
    }

    /**
     * Moves [appItem] out of [folderId] back to a free home cell. Re-indexes the remaining children;
     * if only one is left the folder is dissolved (the last app takes the folder's cell).
     */
    suspend fun removeFromFolder(appItem: AppItem, folderId: Long, columns: Int) {
        db.withTransaction {
            val row = dao.getByKey(folderId, appItem.packageName, appItem.className, appItem.userSerial)
                ?: return@withTransaction
            val (p, x, y) = firstFreeCell(dao.getContainer(HOME), columns)
            dao.moveById(row.id, HOME, p, x, y)
            reindexFolder(folderId)
            dissolveIfNeeded(folderId)
        }
    }

    suspend fun renameFolder(folderId: Long, name: String) {
        dao.renameFolder(folderId, name)
    }

    private suspend fun reindexFolder(folderId: Long) {
        val children = dao.getContainerOrdered(folderId)
        children.forEachIndexed { i, child ->
            if (child.cellX != i || child.cellY != 0 || child.page != 0) {
                dao.moveById(child.id, folderId, 0, i, 0)
            }
        }
    }

    /** Collapses a folder once it holds a single app (move it out) or none (delete the folder). */
    private suspend fun dissolveIfNeeded(folderId: Long) {
        val children = dao.getContainerOrdered(folderId)
        when (children.size) {
            0 -> dao.deleteById(folderId)
            1 -> {
                val folder = dao.getById(folderId) ?: return
                dao.deleteById(folderId) // free the home cell first
                dao.moveById(children.first().id, HOME, folder.page, folder.cellX, folder.cellY)
            }
        }
    }

    /**
     * Moves [source] to a target cell in the [HOME] container, swapping with the cell's occupant if
     * any. Call inside a transaction: the swap parks [source] off-grid so the unique index holds.
     */
    private suspend fun moveOrSwap(source: HomeItemEntity, page: Int, cellX: Int, cellY: Int) {
        if (source.page == page && source.cellX == cellX && source.cellY == cellY) return
        // Widgets occupy a rectangle and aren't swappable in MVP: cancel a drop onto/into one.
        if (cellInWidget(dao.getContainer(HOME), source.id, page, cellX, cellY)) return
        val occupant = dao.getAt(HOME, page, cellX, cellY)
        when {
            occupant == null -> dao.moveById(source.id, HOME, page, cellX, cellY)
            occupant.id == source.id -> Unit
            else -> {
                dao.moveById(source.id, HOME, TEMP_SLOT, TEMP_SLOT, TEMP_SLOT)
                dao.moveById(occupant.id, HOME, source.page, source.cellX, source.cellY)
                dao.moveById(source.id, HOME, page, cellX, cellY)
            }
        }
    }

    private fun homeApp(app: AppItem, container: Long, page: Int, cellX: Int, cellY: Int) =
        HomeItemEntity(
            containerId = container,
            packageName = app.packageName,
            className = app.className,
            userSerial = app.userSerial,
            page = page,
            cellX = cellX,
            cellY = cellY,
        )

    /** True if (page,cellX,cellY) lies within any widget's rectangle in [items] (excluding [excludeId]). */
    private fun cellInWidget(items: List<HomeItemEntity>, excludeId: Long, page: Int, cellX: Int, cellY: Int): Boolean =
        items.any {
            it.id != excludeId && it.isWidget && it.page == page &&
                cellX in it.cellX until (it.cellX + it.spanX) &&
                cellY in it.cellY until (it.cellY + it.spanY)
        }

    private fun firstFreeCell(items: List<HomeItemEntity>, columns: Int): Triple<Int, Int, Int> =
        firstFreeRect(items, columns, 1, 1)

    /** Places a bound widget at the first free [spanX]×[spanY] rectangle on the home grid. */
    suspend fun addWidget(appWidgetId: Int, provider: String, spanX: Int, spanY: Int, columns: Int) {
        db.withTransaction {
            val (page, x, y) = firstFreeRect(dao.getContainer(HOME), columns, spanX, spanY)
            dao.insert(
                HomeItemEntity(
                    containerId = HOME,
                    page = page, cellX = x, cellY = y,
                    spanX = spanX.coerceIn(1, columns.coerceAtLeast(1)),
                    spanY = spanY.coerceIn(1, ROWS),
                    appWidgetId = appWidgetId,
                    widgetProvider = provider,
                ),
            )
        }
    }

    /** Removes a placed widget row (the host id is freed by the caller). */
    suspend fun removeWidget(rowId: Long) {
        dao.deleteById(rowId)
    }

    /** Atomically sets a widget row's full grid bounds (used by both move and resize). Returns false
     *  (no change) if the row is gone, isn't a widget, or the target rect doesn't fit. */
    suspend fun setWidgetBounds(
        rowId: Long, page: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int, columns: Int,
    ): Boolean = db.withTransaction {
        val row = dao.getById(rowId) ?: return@withTransaction false
        if (!row.isWidget) return@withTransaction false
        val sx = spanX.coerceAtLeast(1)
        val sy = spanY.coerceAtLeast(1)
        if (!rectFitsForRow(dao.getContainer(HOME), rowId, page, cellX, cellY, sx, sy, columns)) {
            return@withTransaction false
        }
        dao.moveById(rowId, HOME, page, cellX, cellY)
        dao.updateSpans(rowId, sx, sy)
        true
    }

    companion object {
        /** Rows per home page (fixed for now). */
        const val ROWS = 6

        /** Off-grid parking slot used while swapping two cells inside a transaction. */
        private const val TEMP_SLOT = -1

        /**
         * First top-left cell where a [spanX]×[spanY] rectangle fits on the home grid without
         * overlapping any item (each item occupies its own spanX×spanY cells; apps/folders/shortcuts
         * are 1×1). Spans are clamped to the grid; advances to a fresh trailing page when needed.
         */
        /** True if a [spanX]×[spanY] rect at (page,cellX,cellY) is on-grid and free of every item
         *  except [excludeRowId] (so a widget never blocks its own move/resize). */
        fun rectFitsForRow(
            items: List<HomeItemEntity>, excludeRowId: Long,
            page: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int, columns: Int,
        ): Boolean {
            val cols = columns.coerceAtLeast(1)
            if (cellX < 0 || cellY < 0 || cellX + spanX > cols || cellY + spanY > ROWS) return false
            val occupied = HashSet<Triple<Int, Int, Int>>()
            for (e in items) if (e.id != excludeRowId) for (dx in 0 until e.spanX) for (dy in 0 until e.spanY) {
                occupied += Triple(e.page, e.cellX + dx, e.cellY + dy)
            }
            for (dx in 0 until spanX) for (dy in 0 until spanY) {
                if (Triple(page, cellX + dx, cellY + dy) in occupied) return false
            }
            return true
        }

        fun firstFreeRect(items: List<HomeItemEntity>, columns: Int, spanX: Int, spanY: Int): Triple<Int, Int, Int> {
            val cols = columns.coerceAtLeast(1)
            val sx = spanX.coerceIn(1, cols)
            val sy = spanY.coerceIn(1, ROWS)
            val occupied = HashSet<Triple<Int, Int, Int>>()
            for (e in items) for (dx in 0 until e.spanX) for (dy in 0 until e.spanY) {
                occupied += Triple(e.page, e.cellX + dx, e.cellY + dy)
            }
            var page = 0
            while (true) {
                for (y in 0..ROWS - sy) for (x in 0..cols - sx) {
                    val fits = (0 until sx).all { dx -> (0 until sy).all { dy -> Triple(page, x + dx, y + dy) !in occupied } }
                    if (fits) return Triple(page, x, y)
                }
                page++
            }
        }
    }
}
