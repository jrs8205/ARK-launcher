package org.arkikeskus.launcher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.arkikeskus.launcher.model.AppItem

/**
 * App shortcuts (static + dynamic "deep shortcuts", e.g. a browser's "New tab") shown in the
 * long-press popup, the same ones Pixel Launcher surfaces. Querying requires the launcher to hold the
 * default-home role; if it doesn't (or anything fails), we return an empty list so the popup still
 * shows its normal actions.
 *
 * Also handles **pinning** a shortcut to the home screen (adapted from AOSP Launcher3's model): the
 * system `pinShortcuts` call *replaces* the whole pinned set for a (package, user), so to pin one we
 * re-pin the union of what's already pinned plus the new id, and to unpin we re-pin the remaining set.
 */
object AppShortcuts {

    private const val ALL_FLAGS = LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

    data class Item(
        val packageName: String,
        val id: String,
        val user: UserHandle,
        val userSerial: Long,
        val label: String,
    )

    /** A resolved pinned shortcut: its current label + rasterized icon, for rendering on home. */
    data class Resolved(val label: String, val icon: ImageBitmap?)

    fun query(context: Context, appItem: AppItem): List<Item> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val userManager = context.getSystemService(UserManager::class.java)
        return runCatching {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(appItem.packageName)
                .setQueryFlags(ALL_FLAGS)
            launcherApps.getShortcuts(query, appItem.user)
                .orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .mapNotNull { info ->
                    val label = (info.longLabel ?: info.shortLabel)?.toString()
                    if (label.isNullOrBlank()) {
                        null
                    } else {
                        val serial = userManager?.getSerialNumberForUser(info.userHandle) ?: 0L
                        Item(info.`package`, info.id, info.userHandle, serial, label)
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

    /** Launches a pinned shortcut identified by its stored (package, id, userSerial). */
    fun startById(context: Context, packageName: String, shortcutId: String, userSerial: Long) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        val user = userForSerial(context, userSerial) ?: return
        runCatching {
            launcherApps.startShortcut(packageName, shortcutId, null, null, user)
        }
    }

    /** Pins [item] to the system (union of the already-pinned set for its package + the new id). */
    fun pin(context: Context, item: Item) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        runCatching {
            val pinned = launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery()
                    .setPackage(item.packageName)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),
                item.user,
            ).orEmpty().map { it.id }
            val union = (pinned + item.id).distinct()
            launcherApps.pinShortcuts(item.packageName, union, item.user)
        }
    }

    /** Re-pins exactly [shortcutIds] for (package, user) — used after removing one from home. */
    fun setPinned(context: Context, packageName: String, userSerial: Long, shortcutIds: List<String>) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        val user = userForSerial(context, userSerial) ?: return
        runCatching { launcherApps.pinShortcuts(packageName, shortcutIds, user) }
    }

    /** Resolves a pinned shortcut's current label + icon (rasterized). Null if it no longer exists. */
    fun resolve(context: Context, packageName: String, shortcutId: String, userSerial: Long): Resolved? {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val user = userForSerial(context, userSerial) ?: return null
        return runCatching {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setShortcutIds(listOf(shortcutId))
                .setQueryFlags(ALL_FLAGS)
            val info = launcherApps.getShortcuts(query, user).orEmpty().firstOrNull()
                ?: return@runCatching null
            val label = (info.longLabel ?: info.shortLabel)?.toString() ?: shortcutId
            val density = context.resources.displayMetrics.densityDpi
            val drawable = launcherApps.getShortcutIconDrawable(info, density)
            val icon = drawable?.let {
                val size = 168
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                it.setBounds(0, 0, size, size)
                it.draw(Canvas(bitmap))
                bitmap.asImageBitmap()
            }
            Resolved(label, icon)
        }.getOrNull()
    }

    private fun userForSerial(context: Context, serial: Long): UserHandle? {
        val userManager = context.getSystemService(UserManager::class.java) ?: return null
        return runCatching { userManager.getUserForSerialNumber(serial) }.getOrNull()
    }
}
