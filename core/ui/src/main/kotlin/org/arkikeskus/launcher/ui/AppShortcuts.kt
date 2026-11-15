package org.arkikeskus.launcher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.IconShape
import org.arkikeskus.launcher.model.shapedIcon
import kotlin.math.roundToInt

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
        /** The long label, or the short one when the app gave none. */
        val label: String,
        /** The short label, or the long one; the popup shows it when [label] doesn't fit. */
        val shortLabel: String,
        val icon: ImageBitmap?,
    )

    /** A resolved pinned shortcut: its current label + rasterized icon, for rendering on home. */
    data class Resolved(
        val label: String,
        val icon: ImageBitmap?,
        val enabled: Boolean,
        /** The app's own reason for disabling it, if it gave one. */
        val disabledMessage: String?,
    )

    fun query(context: Context, appItem: AppItem): List<Item> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val userManager = context.getSystemService(UserManager::class.java)
        val metrics = context.resources.displayMetrics
        val iconPx = (SHORTCUT_ICON_SIZE.value * metrics.density).roundToInt()
        return runCatching {
            val offered = launcherApps.getShortcuts(popupShortcutQuery(appItem), appItem.user)
                .orEmpty()
                .filter { it.isEnabled }
                .mapNotNull { info -> labelsOf(info)?.let { labels -> info to labels } }
            sortAndFilterShortcuts(
                offered,
                isManifest = { it.first.isDeclaredInManifest },
                isDynamic = { it.first.isDynamic },
                rank = { it.first.rank },
            ).map { (info, labels) ->
                val (label, shortLabel) = labels
                val serial = userManager?.getSerialNumberForUser(info.userHandle) ?: 0L
                // A failed icon load (e.g. a file icon it may not read) must not drop the shortcut.
                val icon = runCatching {
                    launcherApps.getShortcutIconDrawable(info, metrics.densityDpi)?.toImageBitmap(iconPx)
                }.getOrNull()
                Item(info.`package`, info.id, info.userHandle, serial, label, shortLabel, icon)
            }
        }.getOrDefault(emptyList())
    }

    /** (long-or-short, short-or-long) label, or null when the shortcut has neither. */
    private fun labelsOf(info: ShortcutInfo): Pair<String, String>? {
        val long = info.longLabel?.toString()?.takeIf { it.isNotBlank() }
        val short = info.shortLabel?.toString()?.takeIf { it.isNotBlank() }
        return when {
            long != null -> long to (short ?: long)
            short != null -> short to short
            else -> null
        }
    }

    /** Launches a popup shortcut; one that fails to start says so instead of doing nothing. */
    fun start(context: Context, item: Item) {
        if (!tryStart(context, item.packageName, item.id) { item.user }) sayUnavailable(context, reason = null)
    }

    /**
     * Launches a pinned shortcut identified by its stored (package, id, userSerial). One the app
     * disabled shows the app's reason (or the generic message), and one that fails to start (gone,
     * its profile paused) the generic message — as Launcher3 does — instead of a tap doing nothing.
     */
    fun startById(
        context: Context,
        packageName: String,
        shortcutId: String,
        userSerial: Long,
        enabled: Boolean,
        disabledMessage: String?,
    ) {
        if (!enabled) {
            sayUnavailable(context, disabledMessage)
        } else if (!tryStart(context, packageName, shortcutId) { userForSerial(context, userSerial) }) {
            sayUnavailable(context, reason = null)
        }
    }

    private fun tryStart(context: Context, packageName: String, shortcutId: String, user: () -> UserHandle?): Boolean =
        runCatching {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val profile = user()
            if (launcherApps == null || profile == null) return@runCatching false
            launcherApps.startShortcut(packageName, shortcutId, null, null, profile)
            true
        }.getOrDefault(false)

    private fun sayUnavailable(context: Context, reason: String?) {
        runCatching {
            Toast.makeText(context, reason ?: context.getString(R.string.shortcut_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    /** Pins [item] to the system (union of the already-pinned set for its package + the new id).
     *  Suspends on IO: the query + pinShortcuts round-trips block, and the callers are UI paths.
     *  Returns whether the system pin succeeded, so the caller only stores a home row for a
     *  shortcut the system actually pinned (a failed pin would otherwise leave a dead home cell). */
    suspend fun pin(context: Context, item: Item): Boolean = withContext(Dispatchers.IO) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return@withContext false
        runCatching {
            val pinned = launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery()
                    .setPackage(item.packageName)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),
                item.user,
            ).orEmpty().map { it.id }
            val union = (pinned + item.id).distinct()
            launcherApps.pinShortcuts(item.packageName, union, item.user)
            true
        }.getOrDefault(false)
    }

    /** Re-pins exactly [shortcutIds] for (package, user) — used after removing one from home.
     *  Suspends on IO like [pin]. */
    suspend fun setPinned(
        context: Context,
        packageName: String,
        userSerial: Long,
        shortcutIds: List<String>,
    ): Unit = withContext(Dispatchers.IO) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return@withContext
        val user = userForSerial(context, userSerial) ?: return@withContext
        runCatching { launcherApps.pinShortcuts(packageName, shortcutIds, user) }
        Unit
    }

    /** Resolves a pinned shortcut's current label, icon (rasterized, in the icon [shape] the user picked)
     *  and state — the app can disable a pinned shortcut, which then stays pinned with its reason. Null if
     *  it no longer exists. */
    fun resolve(
        context: Context,
        packageName: String,
        shortcutId: String,
        userSerial: Long,
        shape: IconShape = IconShape.SYSTEM,
    ): Resolved? {
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
            // A failed icon load must not hide the shortcut: it would leave an empty but occupied cell.
            val icon = runCatching {
                launcherApps.getShortcutIconDrawable(info, density)?.let { shortcutIcon(it, shape, 168) }
            }.getOrNull()
            Resolved(
                label = label,
                icon = icon,
                enabled = info.isEnabled,
                disabledMessage = info.disabledMessage?.toString()?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun userForSerial(context: Context, serial: Long): UserHandle? {
        val userManager = context.getSystemService(UserManager::class.java) ?: return null
        return runCatching { userManager.getUserForSerialNumber(serial) }.getOrNull()
    }

    private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, sizePx, sizePx)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}

/**
 * A pinned shortcut's picture in the icon [shape] the user picked (issue #26), by the same rule as an app's
 * icon: its layers cut to the shape, or a picture without layers smaller on a plate of it. With the
 * system's shape it is drawn as Android hands it over.
 */
internal fun shortcutIcon(icon: Drawable, shape: IconShape, sizePx: Int): ImageBitmap {
    if (shape != IconShape.SYSTEM) return shapedIcon(icon, shape, sizePx).asImageBitmap()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    icon.setBounds(0, 0, sizePx, sizePx)
    icon.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/**
 * What the long-press popup asks for, as Launcher3's `ShortcutRequest.PUBLISHED` + `withContainer`: only
 * the shortcuts the app offers now (a home-pinned one it has dropped stays on home only), and only for
 * the pressed icon's own activity — a package can publish shortcuts for several, e.g. YouTube for each
 * of its hidden alternative icons.
 */
internal fun popupShortcutQuery(appItem: AppItem): LauncherApps.ShortcutQuery =
    LauncherApps.ShortcutQuery()
        .setPackage(appItem.packageName)
        .setActivity(appItem.componentName)
        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)

private const val MAX_POPUP_SHORTCUTS = 4
private const val MIN_DYNAMIC_SHORTCUTS = 2

/**
 * The popup's pick from an icon's published shortcuts, as AOSP Launcher3's
 * `PopupPopulator.sortAndFilterShortcuts` (Apache-2.0): the app's fixed (manifest) shortcuts first, each
 * kind by rank — their ranks are separate sequences — and at most [MAX_POPUP_SHORTCUTS], of which up to
 * [MIN_DYNAMIC_SHORTCUTS] dynamic ones (e.g. recent chats) displace the last fixed ones.
 */
internal fun <T> sortAndFilterShortcuts(
    shortcuts: List<T>,
    isManifest: (T) -> Boolean,
    isDynamic: (T) -> Boolean,
    rank: (T) -> Int,
): List<T> {
    val sorted = shortcuts.sortedWith(compareBy<T>({ !isManifest(it) }, { rank(it) }))
    if (sorted.size <= MAX_POPUP_SHORTCUTS) return sorted
    val picked = ArrayList<T>(MAX_POPUP_SHORTCUTS)
    var dynamicCount = 0
    for (shortcut in sorted) {
        if (picked.size < MAX_POPUP_SHORTCUTS) {
            picked += shortcut
            if (isDynamic(shortcut)) dynamicCount++
        } else if (isDynamic(shortcut) && dynamicCount < MIN_DYNAMIC_SHORTCUTS) {
            dynamicCount++
            // The picked list is fixed shortcuts followed by dynamic ones: drop the last fixed one.
            picked.removeAt(picked.size - dynamicCount)
            picked += shortcut
        }
    }
    return picked
}
