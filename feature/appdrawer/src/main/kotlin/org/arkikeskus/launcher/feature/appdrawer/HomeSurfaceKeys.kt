package org.arkikeskus.launcher.feature.appdrawer

import org.arkikeskus.launcher.data.local.HomeItemEntity

/**
 * The keys of the apps that are on the home screen AS SHOWN, for "Hide apps that are on the home
 * screen": the apps on the pages, inside the pages' folders, and in the dock as HomeViewModel shows it
 * -- only while the dock is on ([dockEnabled]), only its rows in slot order that render (a folder, or an
 * app that is [installed]) up to [dockColumns], and those folders' apps. A dock row past the columns or
 * a dock that is off shows nothing, so its apps stay in the drawer.
 */
internal fun homeSurfaceKeys(
    items: List<HomeItemEntity>,
    installed: Set<String>,
    dockEnabled: Boolean,
    dockColumns: Int,
): Set<String> {
    val children = items
        .filter { it.containerId != HomeItemEntity.HOME && it.containerId != HomeItemEntity.DOCK }
        .groupBy { it.containerId }
    val keys = HashSet<String>()
    fun folderApps(folderId: Long) = children[folderId].orEmpty().filter { it.isApp }.forEach { keys += it.key }
    for (row in items) {
        if (row.containerId != HomeItemEntity.HOME) continue
        if (row.isApp) keys += row.key else if (row.isFolder) folderApps(row.id)
    }
    if (dockEnabled) {
        items
            .filter { it.containerId == HomeItemEntity.DOCK }
            .sortedWith(compareBy({ it.page }, { it.cellY }, { it.cellX }))
            .filter { it.isFolder || (it.isApp && it.key in installed) }
            .take(dockColumns.coerceAtLeast(0))
            .forEach { row -> if (row.isFolder) folderApps(row.id) else keys += row.key }
    }
    return keys
}
