package org.arkikeskus.launcher.data.local

import androidx.room.ColumnInfo
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
 *  - **App or folder in the dock**: [containerId] = [DOCK], [page] 0, [cellY] 0, [cellX] = the slot
 *    (0..n-1, the dock's order). A dock folder's children point at its id like a home folder's.
 *
 * The unique index on (homeProfileId, containerId, page, cellX, cellY) enforces "one item per cell"
 * *within each container of each home profile* (the home grid, the dock and each folder are independent
 * cell spaces, and every profile has its own home grid and dock) — see [HomeLayoutRepository].
 */
@Entity(
    tableName = "home_items",
    indices = [Index(value = ["homeProfileId", "containerId", "page", "cellX", "cellY"], unique = true)],
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
    val spanX: Int = 1,
    val spanY: Int = 1,
    /** Non-null → this row is a bound app widget occupying spanX×spanY cells. */
    val appWidgetId: Int? = null,
    /** The widget provider's ComponentName.flattenToString() (set when [appWidgetId] is). */
    val widgetProvider: String? = null,
    /** Non-null → a built-in launcher widget (see [BUILTIN_SMARTSPACE]) occupying spanX×spanY cells. */
    val builtinType: String? = null,
    /** The home profile this row belongs to (`HomeProfile.id`); every row of before profiles is in the
     *  first. The profile-scoped [HomeItemDao] stamps it on insert, whatever the row says. */
    @ColumnInfo(defaultValue = "1") val homeProfileId: Long = 1,
) {
    /** Matches AppItem.key so app entities can be resolved against the live app list. */
    val key: String get() = "$packageName/$className/$userSerial"

    val isFolder: Boolean get() = folderName != null

    val isShortcut: Boolean get() = shortcutId != null

    val isWidget: Boolean get() = appWidgetId != null

    val isBuiltin: Boolean get() = builtinType != null

    /** A plain app icon (on home or inside a folder), resolved through [key]. */
    val isApp: Boolean
        get() = folderName == null && shortcutId == null && widgetProvider == null &&
            builtinType == null && packageName.isNotEmpty()

    /** True for any row that occupies a spanX×spanY footprint (an app widget — bound or a restored
     *  placeholder — or a built-in widget); everything else is 1×1. */
    val hasFootprint: Boolean get() = widgetProvider != null || builtinType != null

    companion object {
        /** [containerId] sentinel meaning "placed directly on the home screen". */
        const val HOME = -1L

        /** [containerId] sentinel meaning "in the dock" (Launcher3's hotseat): [cellX] is the slot. */
        const val DOCK = -2L

        const val BUILTIN_PROFILE_SWITCH = "profile_switch"

        fun profileSwitchType(target: Long): String {
            require(target > 0)
            return "$BUILTIN_PROFILE_SWITCH:$target"
        }

        fun profileSwitchTarget(type: String?): Long? {
            val id = type?.substringAfter(':', "")?.toLongOrNull() ?: return null
            return id.takeIf { it > 0 && profileSwitchType(it) == type }
        }

        /** [builtinType] of the clock + date + next-calendar-event widget. */
        const val BUILTIN_SMARTSPACE = "smartspace"

        /** [builtinType] of the notifications widget (active notifications as app icons). */
        const val BUILTIN_NOTIFICATIONS = "notifications"

        /** [builtinType] of the battery widget (a ring gauge + percent, app-icon sized). */
        const val BUILTIN_BATTERY = "battery"

        /** [builtinType] of the people widget (conversations grouped by person, as quiet tiles). */
        const val BUILTIN_PEOPLE = "people"
    }
}
