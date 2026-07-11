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

/** One row's target grid position (and sanitized span) in a [HomeLayoutRepository.reflowPlan]. */
data class ReflowPlacement(
    val id: Long, val page: Int, val cellX: Int, val cellY: Int, val spanX: Int, val spanY: Int,
)

/** Where [HomeLayoutRepository.firstRectWithPush] places a new rect, plus the displacements
 *  (rowId → new cell on [page]) that free it. Empty [moved] = the rect was genuinely free. */
data class PushPlacement(val page: Int, val cellX: Int, val cellY: Int, val moved: Map<Long, Pair<Int, Int>>)

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

    suspend fun addToHome(appItem: AppItem, columns: Int, rows: Int) {
        // Atomic count-then-insert: without a transaction a fast double call could read "absent" twice
        // and place the same app into two different cells.
        db.withTransaction {
            if (dao.count(HOME, appItem.packageName, appItem.className, appItem.userSerial) > 0) {
                return@withTransaction
            }
            val (page, cellX, cellY) = firstFreeCell(dao.getContainer(HOME), columns, rows)
            dao.insert(homeApp(appItem, HOME, page, cellX, cellY))
        }
    }

    /** Moves/swaps a home item to a target home cell. Returns false if the item is gone OR the target
     *  is inside a widget's rectangle (so the caller rolls its optimistic placement back). */
    suspend fun moveItem(appItem: AppItem, page: Int, cellX: Int, cellY: Int): Boolean =
        db.withTransaction {
            val source = dao.getByKey(HOME, appItem.packageName, appItem.className, appItem.userSerial)
                ?: return@withTransaction false
            moveOrSwap(source, page, cellX, cellY)
        }

    /** Places [appItem] on the home screen at a cell (used for a dock→home drop), swapping/falling back. */
    suspend fun placeAt(appItem: AppItem, page: Int, cellX: Int, cellY: Int, columns: Int, rows: Int): Boolean =
        db.withTransaction {
            val existing = dao.getByKey(HOME, appItem.packageName, appItem.className, appItem.userSerial)
            if (existing != null) {
                return@withTransaction moveOrSwap(existing, page, cellX, cellY)
            }
            val items = dao.getContainer(HOME)
            val (p, x, y) = if (dao.getAt(HOME, page, cellX, cellY) == null &&
                !cellInWidget(items, -1L, page, cellX, cellY)
            ) {
                Triple(page, cellX, cellY)
            } else {
                firstFreeCell(items, columns, rows)
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
        }

    /** Pins a deep shortcut at the first free home cell (no-op if it's already on home). */
    suspend fun addShortcut(packageName: String, shortcutId: String, userSerial: Long, columns: Int, rows: Int) {
        db.withTransaction {
            val present = dao.getContainer(HOME).any {
                it.shortcutId == shortcutId && it.packageName == packageName && it.userSerial == userSerial
            }
            if (present) return@withTransaction
            val (page, x, y) = firstFreeCell(dao.getContainer(HOME), columns, rows)
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
     * Repacks the top-level home items (apps, folders, widgets) into a [columns]-wide grid via
     * [reflowPlan]: reading order is preserved, widgets keep (or shrink to fit) their footprint and
     * the packing reserves it, overflow spills onto later pages. Folder *contents* are untouched.
     */
    suspend fun reflow(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        db.withTransaction {
            val items = dao.getContainerOrdered(HOME)
            if (items.isEmpty()) return@withTransaction
            val plan = reflowPlan(items, columns, rows)
            // Park everything off-grid first (unique temp cells) so the repack can't collide.
            items.forEachIndexed { i, row -> dao.moveById(row.id, HOME, -1, -(i + 1), -1) }
            items.zip(plan).forEach { (row, p) ->
                dao.moveById(p.id, HOME, p.page, p.cellX, p.cellY)
                // Persist a span shrink (widget wider/taller than the new grid, or a corrupt span).
                if (p.spanX != row.spanX || p.spanY != row.spanY) dao.updateSpans(p.id, p.spanX, p.spanY)
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

    /** Moves [appItem] off the home screen into [folderId], appended after its current children.
     *  If the folder already contains the app the drop merges: the direct home icon is removed
     *  instead of inserting a duplicate child (duplicate keys crash the folder grid). */
    suspend fun addToFolder(appItem: AppItem, folderId: Long) {
        db.withTransaction {
            val row = dao.getByKey(HOME, appItem.packageName, appItem.className, appItem.userSerial)
                ?: return@withTransaction
            if (dao.getByKey(folderId, appItem.packageName, appItem.className, appItem.userSerial) != null) {
                dao.deleteById(row.id)
                return@withTransaction
            }
            val order = dao.childCount(folderId)
            dao.moveById(row.id, folderId, 0, order, 0)
        }
    }

    /**
     * Moves [appItem] out of [folderId] back to a free home cell. Re-indexes the remaining children;
     * if only one is left the folder is dissolved (the last app takes the folder's cell).
     */
    suspend fun removeFromFolder(appItem: AppItem, folderId: Long, columns: Int, rows: Int) {
        db.withTransaction {
            val row = dao.getByKey(folderId, appItem.packageName, appItem.className, appItem.userSerial)
                ?: return@withTransaction
            val (p, x, y) = firstFreeCell(dao.getContainer(HOME), columns, rows)
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
     * Returns false (no change) when the target is inside a widget's rectangle (bound OR a restored
     * placeholder) — the caller uses this to roll back its optimistic placement so the icon snaps back
     * instead of ending up hidden under the widget.
     */
    private suspend fun moveOrSwap(source: HomeItemEntity, page: Int, cellX: Int, cellY: Int): Boolean {
        if (source.page == page && source.cellX == cellX && source.cellY == cellY) return true
        // Widgets occupy a rectangle and aren't swappable in MVP: cancel a drop onto/into one.
        if (cellInWidget(dao.getContainer(HOME), source.id, page, cellX, cellY)) return false
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
        return true
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

    /**
     * True if (page,cellX,cellY) lies within any widget's rectangle in [items] (excluding [excludeId]).
     * Covers bound widgets, restored-but-unbound placeholders AND built-in widgets ([HomeItemEntity.hasFootprint])
     * — each still occupies its spanX×spanY footprint, so a drop must not land inside it (else the
     * app hides under it / a swap corrupts the multi-cell row). `isWidget` alone missed placeholders.
     */
    private fun cellInWidget(items: List<HomeItemEntity>, excludeId: Long, page: Int, cellX: Int, cellY: Int): Boolean =
        items.any {
            it.id != excludeId && it.hasFootprint && it.page == page &&
                cellX in it.cellX until (it.cellX + it.spanX) &&
                cellY in it.cellY until (it.cellY + it.spanY)
        }

    private fun firstFreeCell(items: List<HomeItemEntity>, columns: Int, rows: Int): Triple<Int, Int, Int> =
        firstFreeRect(items, columns, 1, 1, rows)

    /** Applies a [PushPlacement]'s displacements: park off-grid first (unique temp cells) so the
     *  unique (page,cellX,cellY) index can't be violated mid-update, then place at the final cells. */
    private suspend fun applyPush(p: PushPlacement) {
        if (p.moved.isEmpty()) return
        var temp = -1
        for (id in p.moved.keys) { dao.moveById(id, HOME, temp, temp, temp); temp-- }
        for ((id, pos) in p.moved) dao.moveById(id, HOME, p.page, pos.first, pos.second)
    }

    /** Places a bound widget at the first [spanX]×[spanY] rectangle on the home grid, pushing
     *  overlapping icons aside before spilling onto a fresh page (see [firstRectWithPush]). */
    suspend fun addWidget(appWidgetId: Int, provider: String, spanX: Int, spanY: Int, columns: Int, rows: Int) {
        db.withTransaction {
            val placement = firstRectWithPush(dao.getContainer(HOME), columns, spanX, spanY, rows)
            applyPush(placement)
            val (page, x, y) = Triple(placement.page, placement.cellX, placement.cellY)
            dao.insert(
                HomeItemEntity(
                    containerId = HOME,
                    page = page, cellX = x, cellY = y,
                    spanX = spanX.coerceIn(1, columns.coerceAtLeast(1)),
                    spanY = spanY.coerceIn(1, rows.coerceAtLeast(1)),
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

    /** Places a built-in widget (e.g. [HomeItemEntity.BUILTIN_SMARTSPACE]) at the first rectangle,
     *  pushing overlapping icons aside before spilling onto a fresh page (see [firstRectWithPush]). */
    suspend fun addBuiltin(type: String, spanX: Int, spanY: Int, columns: Int, rows: Int) {
        db.withTransaction {
            val placement = firstRectWithPush(dao.getContainer(HOME), columns, spanX, spanY, rows)
            applyPush(placement)
            val (page, x, y) = Triple(placement.page, placement.cellX, placement.cellY)
            dao.insert(
                HomeItemEntity(
                    containerId = HOME,
                    page = page, cellX = x, cellY = y,
                    spanX = spanX.coerceIn(1, columns.coerceAtLeast(1)),
                    spanY = spanY.coerceIn(1, rows.coerceAtLeast(1)),
                    builtinType = type,
                ),
            )
        }
    }

    /** True when nothing has ever been placed on the home surface (fresh install). */
    suspend fun isHomeEmpty(): Boolean = dao.getContainer(HOME).isEmpty()

    /**
     * Turns bound widget rows whose device-local id is NOT in [validIds] back into unbound
     * tap-to-set-up placeholders. After a Google Auto Backup / device-to-device restore the Room DB
     * comes back with the OLD device's appWidgetIds — ids this host never allocated — which would
     * otherwise render as invisible dead zones on the grid.
     */
    suspend fun unbindStaleWidgets(validIds: Set<Int>) {
        db.withTransaction {
            dao.getContainer(HOME).forEach { row ->
                val id = row.appWidgetId
                if (id != null && id !in validIds) dao.clearWidgetId(row.id)
            }
        }
    }

    /** Binds a restored widget placeholder row to a freshly allocated [appWidgetId] on this device,
     *  turning it back into a live widget (its spanX/spanY were preserved from the backup). */
    suspend fun bindRestoredWidget(rowId: Long, appWidgetId: Int) {
        dao.updateWidgetId(rowId, appWidgetId)
    }

    /** Device-local ids of all bound widgets, for reconciling leftover ids in the AppWidgetHost. */
    suspend fun boundWidgetIds(): Set<Int> = dao.boundWidgetIds().toSet()

    /** Atomically sets a widget row's full grid bounds (used by both move and resize). If the target
     *  rect is occupied, the overlapping items are pushed aside to free cells on the same page
     *  ([ReorderPlanner]); returns false (no change) if the row is gone, isn't a widget, or no
     *  arrangement fits. */
    suspend fun setWidgetBounds(
        rowId: Long, page: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int, columns: Int, rows: Int,
    ): Boolean = db.withTransaction {
        val row = dao.getById(rowId) ?: return@withTransaction false
        if (!row.isWidget && !row.isBuiltin) return@withTransaction false
        val sx = spanX.coerceAtLeast(1)
        val sy = spanY.coerceAtLeast(1)
        val items = dao.getContainer(HOME)
        if (rectFitsForRow(items, rowId, page, cellX, cellY, sx, sy, columns, rows)) {
            dao.moveById(rowId, HOME, page, cellX, cellY)
            dao.updateSpans(rowId, sx, sy)
            return@withTransaction true
        }
        // Target is occupied: try to push the overlapping items aside.
        val cols = columns.coerceAtLeast(1)
        val plan = ReorderPlanner.planFit(
            items.map { ReorderPlanner.Rect(it.id, it.page, it.cellX, it.cellY, it.spanX, it.spanY) },
            rowId, page, cellX, cellY, sx, sy, cols, rows.coerceAtLeast(1),
        ) ?: return@withTransaction false
        // Park the widget + every displaced item off-grid (unique temp cells) so the unique
        // (page,cellX,cellY) index can't be violated mid-update, then place each at its final cell.
        var temp = -1
        dao.moveById(rowId, HOME, temp, temp, temp); temp--
        for (id in plan.keys) { dao.moveById(id, HOME, temp, temp, temp); temp-- }
        for ((id, pos) in plan) dao.moveById(id, HOME, page, pos.first, pos.second)
        dao.moveById(rowId, HOME, page, cellX, cellY)
        dao.updateSpans(rowId, sx, sy)
        true
    }

    /**
     * Deletes every row whose app or shortcut is gone per [isInstalled]: an uninstalled app's rows
     * never render, but they still occupy their cells and silently block placement, resizes and
     * moves with no visible reason. [isInstalled] must be fail-safe — answer true when unsure
     * (locked profile, transient error) so a hiccup can never wipe real items.
     */
    suspend fun removeStaleAppRows(isInstalled: (packageName: String, userSerial: Long) -> Boolean): Int =
        db.withTransaction {
            val stale = staleAppRowIds(dao.getAll(), isInstalled)
            stale.forEach { dao.deleteById(it) }
            stale.size
        }

    /** Uninstall-event cleanup: every app/shortcut/folder-child row of [packageName] in the profile
     *  [userSerial] (widgets, built-ins and folder rows are never touched). */
    suspend fun removeAppRowsForPackage(packageName: String, userSerial: Long): Int =
        removeStaleAppRows { pkg, serial -> !(pkg == packageName && serial == userSerial) }

    companion object {
        /** DEFAULT rows per home page — the live value is the user's homeRows setting (5..8);
         *  this constant only backs default parameters and old backups without home_rows. */
        const val ROWS = 6

        /** Hard cap on home pages. The pager offers one trailing page past the cap, so the highest
         *  legal item page INDEX is MAX_PAGES itself; restore accepts 0..MAX_PAGES inclusive. */
        const val MAX_PAGES = 50

        /**
         * Plans a repack of the given HOME rows into a [columns]-wide grid, in reading order.
         * Widgets keep their spanX×spanY footprint (shrunk to fit the new grid if needed) and the
         * cells they cover are reserved, so no later item can be packed inside a widget's rectangle
         * (the old 1×1-slot packing buried icons under widgets). Non-widget rows are always 1×1.
         */
        fun reflowPlan(rows: List<HomeItemEntity>, columns: Int, gridRows: Int = ROWS): List<ReflowPlacement> {
            val cols = columns.coerceAtLeast(1)
            val rws = gridRows.coerceAtLeast(1)
            val placed = ArrayList<HomeItemEntity>(rows.size)
            val plan = ArrayList<ReflowPlacement>(rows.size)
            for (row in rows) {
                val widget = row.hasFootprint
                val sx = if (widget) row.spanX.coerceIn(1, cols) else 1
                val sy = if (widget) row.spanY.coerceIn(1, rws) else 1
                val (page, x, y) = firstFreeRect(placed, cols, sx, sy, rws)
                plan += ReflowPlacement(row.id, page, x, y, sx, sy)
                // Occupancy carrier for the next iteration's firstFreeRect scan.
                placed += HomeItemEntity(page = page, cellX = x, cellY = y, spanX = sx, spanY = sy)
            }
            return plan
        }

        /** Off-grid parking slot used while swapping two cells inside a transaction. */
        private const val TEMP_SLOT = -1

        /** Pure selection for [removeStaleAppRows]: plain app rows, pinned-shortcut rows and folder
         *  children whose (package, profile) fails [isInstalled] — never widget/builtin/folder rows. */
        fun staleAppRowIds(
            items: List<HomeItemEntity>,
            isInstalled: (packageName: String, userSerial: Long) -> Boolean,
        ): List<Long> {
            val verdict = HashMap<Pair<String, Long>, Boolean>()
            return items.filter { row ->
                row.folderName == null && row.builtinType == null && row.widgetProvider == null &&
                    row.packageName.isNotEmpty() &&
                    !verdict.getOrPut(row.packageName to row.userSerial) {
                        isInstalled(row.packageName, row.userSerial)
                    }
            }.map { it.id }
        }

        /** True if a [spanX]×[spanY] rect at (page,cellX,cellY) is on-grid and free of every item
         *  except [excludeRowId] (so a widget never blocks its own move/resize). */
        fun rectFitsForRow(
            items: List<HomeItemEntity>, excludeRowId: Long,
            page: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int, columns: Int, rows: Int = ROWS,
        ): Boolean {
            val cols = columns.coerceAtLeast(1)
            if (cellX < 0 || cellY < 0 || cellX + spanX > cols || cellY + spanY > rows.coerceAtLeast(1)) return false
            val occupied = HashSet<Triple<Int, Int, Int>>()
            for (e in items) if (e.id != excludeRowId) for (dx in 0 until e.spanX) for (dy in 0 until e.spanY) {
                occupied += Triple(e.page, e.cellX + dx, e.cellY + dy)
            }
            for (dx in 0 until spanX) for (dy in 0 until spanY) {
                if (Triple(page, cellX + dx, cellY + dy) in occupied) return false
            }
            return true
        }

        /**
         * First top-left cell where a [spanX]×[spanY] rectangle fits on the home grid without
         * overlapping any item (each item occupies its own spanX×spanY cells; apps/folders/shortcuts
         * are 1×1). Spans are clamped to the grid; advances to a fresh trailing page when needed.
         */
        /**
         * Like [firstFreeRect], but before exiling the rect to a fresh page it tries to PUSH items
         * aside on the existing pages ([ReorderPlanner.planFit], the same plan move/resize commits).
         * A full-width widget can only start at column 0, so without this a single icon on a row
         * disqualifies the whole row and a picker add silently lands on a page the user isn't
         * looking at — the "the clock won't install" report.
         */
        fun firstRectWithPush(
            items: List<HomeItemEntity>, columns: Int, spanX: Int, spanY: Int, rows: Int = ROWS,
        ): PushPlacement {
            val cols = columns.coerceAtLeast(1)
            val rws = rows.coerceAtLeast(1)
            val sx = spanX.coerceIn(1, cols)
            val sy = spanY.coerceIn(1, rws)
            val free = firstFreeRect(items, cols, sx, sy, rws)
            val maxPage = items.filter { it.page >= 0 }.maxOfOrNull { it.page } ?: -1
            if (free.first <= maxPage) {
                return PushPlacement(free.first, free.second, free.third, emptyMap())
            }
            val rects = items.map { ReorderPlanner.Rect(it.id, it.page, it.cellX, it.cellY, it.spanX, it.spanY) }
            for (page in 0..maxPage) {
                for (y in 0..rws - sy) for (x in 0..cols - sx) {
                    val plan = ReorderPlanner.planFit(rects, NO_ROW, page, x, y, sx, sy, cols, rws)
                    if (plan != null) return PushPlacement(page, x, y, plan)
                }
            }
            return PushPlacement(free.first, free.second, free.third, emptyMap())
        }

        /** A row id no real row carries — excludes nothing from [ReorderPlanner.planFit]'s collisions. */
        private const val NO_ROW = -1L

        fun firstFreeRect(items: List<HomeItemEntity>, columns: Int, spanX: Int, spanY: Int, rows: Int = ROWS): Triple<Int, Int, Int> {
            val cols = columns.coerceAtLeast(1)
            val rws = rows.coerceAtLeast(1)
            val sx = spanX.coerceIn(1, cols)
            val sy = spanY.coerceIn(1, rws)
            val occupied = HashSet<Triple<Int, Int, Int>>()
            for (e in items) for (dx in 0 until e.spanX) for (dy in 0 until e.spanY) {
                occupied += Triple(e.page, e.cellX + dx, e.cellY + dy)
            }
            var page = 0
            while (true) {
                for (y in 0..rws - sy) for (x in 0..cols - sx) {
                    val fits = (0 until sx).all { dx -> (0 until sy).all { dy -> Triple(page, x + dx, y + dy) !in occupied } }
                    if (fits) return Triple(page, x, y)
                }
                page++
            }
        }
    }
}
