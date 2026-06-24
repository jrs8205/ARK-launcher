package org.arkikeskus.launcher.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry in the home layout. A single table holds three shapes, distinguished by [containerId]
 * and [folderName]:
 *  - **App on home**: [containerId] = [HOME], [folderName] = null, package set, at a home cell.
 *  - **Folder on home**: [containerId] = [HOME], [folderName] != null, package empty, at a home cell.
 *    Its [id] is the container id its children point at.
 *  - **App inside a folder**: [containerId] = the folder's id; [page]/[cellX]/[cellY] are the in-folder
 *    order rather than a home cell.
 *  - **Pinned deep shortcut on home**: [containerId] = [HOME], [shortcutId] != null, [packageName] is
 *    the publishing app, at a home cell. Launched via LauncherApps.startShortcut.
 *
 * The unique index on (containerId, page, cellX, cellY) enforces "one item per cell" *within each
 * container* (the home grid and each folder are independent cell spaces) — see [HomeLayoutRepository].
 */
@Entity(
    tableName = "home_items",
    indices = [Index(value = ["containerId", "page", "cellX", "cellY"], unique = true)],
)
data class HomeItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val containerId: Long = HOME,
    val folderName: String? = null,
    val packageName: String = "",
    val className: String = "",
    val userSerial: Long = 0,
    /** Non-null → this row is a pinned deep shortcut published by [packageName]. */
    val shortcutId: String? = null,
    val page: Int,
    val cellX: Int,
    val cellY: Int,
) {
    /** Matches AppItem.key so app entities can be resolved against the live app list. */
    val key: String get() = "$packageName/$className/$userSerial"

    val isFolder: Boolean get() = folderName != null

    val isShortcut: Boolean get() = shortcutId != null

    companion object {
        /** [containerId] sentinel meaning "placed directly on the home screen". */
        const val HOME = -1L
    }
}
