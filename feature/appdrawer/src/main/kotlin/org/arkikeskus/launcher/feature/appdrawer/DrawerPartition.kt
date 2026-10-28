package org.arkikeskus.launcher.feature.appdrawer

import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.data.WorkProfile
import org.arkikeskus.launcher.model.AppItem

/** The two pages of the idle drawer when a work profile has something to show. */
enum class DrawerTab { Personal, Work }

/**
 * The idle drawer's lists. [personal] and [work] are the two flat A–Z grids (an app is in exactly
 * one of them); [folders] are the user's drawer folders with their members resolved, work apps
 * included — a folder is the user's own grouping, not a grid, so a work app in one is on the Work
 * tab as well; [folderCandidates] is what a folder's "Add apps" sheet offers: every visible app of
 * either profile that is not in a folder yet.
 */
internal data class DrawerPartition(
    val personal: List<AppItem>,
    val work: List<AppItem>,
    val folders: List<DrawerFolderUi>,
    val folderCandidates: List<AppItem>,
)

/**
 * Splits [apps] (already A–Z) for the idle drawer: hidden apps leave everything, the apps of the
 * profile with [workSerial] go to the Work grid, the rest to the Personal grid minus the members
 * of [folders]. With no work profile ([workSerial] null) the result is the 0.9.1 drawer. The folders
 * keep their stored order (the user's: creation, then moves) unless [sortFolders] asks for A–Z,
 * case aside like the app list.
 */
internal fun partitionDrawer(
    apps: List<AppItem>,
    hidden: Set<String>,
    folders: List<DrawerFolder>,
    workSerial: Long?,
    sortFolders: Boolean,
): DrawerPartition {
    val visible = if (hidden.isEmpty()) apps else apps.filterNot { it.key in hidden }
    val (work, personal) = visible.partition { workSerial != null && it.userSerial == workSerial }
    if (folders.isEmpty()) return DrawerPartition(personal, work, emptyList(), visible)
    val byKey = visible.associateBy { it.key }
    val ordered = if (sortFolders) folders.sortedBy { it.name.lowercase() } else folders
    val folderUis = ordered.map { f -> DrawerFolderUi(f.id, f.name, f.appKeys.mapNotNull { byKey[it] }) }
    val inFolder = folders.flatMapTo(HashSet()) { it.appKeys }
    return DrawerPartition(
        personal = personal.filterNot { it.key in inFolder },
        work = work,
        folders = folderUis,
        folderCandidates = visible.filterNot { it.key in inFolder },
    )
}

/** Launcher3's `hasWorkApps()` rule plus the paused case, so "Unpause" is always reachable. */
internal fun workTabVisible(profile: WorkProfile?, work: List<AppItem>): Boolean =
    profile != null && (profile.paused || work.isNotEmpty())

/** The tab the pager shows: a Work selection only means something while the Work tab exists. */
internal fun effectiveTab(selected: DrawerTab, hasWorkTab: Boolean): DrawerTab =
    if (hasWorkTab) selected else DrawerTab.Personal
