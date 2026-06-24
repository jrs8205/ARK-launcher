package org.arkikeskus.launcher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import org.arkikeskus.launcher.model.AppItem

/**
 * App shortcuts (static + dynamic "deep shortcuts", e.g. a browser's "New tab") shown in the
 * long-press popup, the same ones Pixel Launcher surfaces. Querying requires the launcher to hold the
 * default-home role; if it doesn't (or anything fails), we return an empty list so the popup still
 * shows its normal actions.
 */
object AppShortcuts {

    data class Item(
        val packageName: String,
        val id: String,
        val user: UserHandle,
        val label: String,
    )

    fun query(context: Context, appItem: AppItem): List<Item> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        return runCatching {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(appItem.packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
            launcherApps.getShortcuts(query, appItem.user)
                .orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .mapNotNull { info ->
                    val label = (info.longLabel ?: info.shortLabel)?.toString()
                    if (label.isNullOrBlank()) {
                        null
                    } else {
                        Item(info.`package`, info.id, info.userHandle, label)
                    }
                }
        }.getOrDefault(emptyList())
    }

    fun start(context: Context, item: Item) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        runCatching {
            launcherApps.startShortcut(item.packageName, item.id, null, null, item.user)
        }
    }
}
