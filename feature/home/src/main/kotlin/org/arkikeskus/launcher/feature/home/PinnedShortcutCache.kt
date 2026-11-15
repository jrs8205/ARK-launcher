package org.arkikeskus.launcher.feature.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.arkikeskus.launcher.model.IconShape
import org.arkikeskus.launcher.ui.AppShortcuts

/**
 * The resolved label, icon and state of the shortcuts pinned on home. The home state resolves the
 * missing entries off the main thread; [invalidate] and [shortcutsChanged] drop an app's entries
 * when it changes and bump [generation], which the home state observes to re-resolve them.
 *
 * A resolution that was already running when its app's shortcuts changed carries stale data: [put]
 * drops it (the app's [epoch] moved on) and bumps [generation] so the next pass resolves afresh.
 */
internal class PinnedShortcutCache {
    /** [shape] is the icon shape the shortcut's picture was drawn in: resolved for another shape, it is another entry. */
    data class Key(
        val packageName: String,
        val shortcutId: String,
        val userSerial: Long,
        val shape: IconShape = IconShape.SYSTEM,
    )

    private val entries = HashMap<Key, AppShortcuts.Resolved>()
    private val epochs = HashMap<String, Int>()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    @Synchronized
    operator fun get(key: Key): AppShortcuts.Resolved? = entries[key]

    /** Read before resolving one of [packageName]'s shortcuts; hand it back to [put]. */
    @Synchronized
    fun epoch(packageName: String): Int = epochs[packageName] ?: 0

    @Synchronized
    fun put(key: Key, value: AppShortcuts.Resolved, epoch: Int) {
        if (epoch(key.packageName) == epoch) {
            entries[key] = value
        } else {
            _generation.update { it + 1 }
        }
    }

    /** A package event: drops [packageName]'s entries, and home recomputes only if there were any —
     *  most package events are for apps with nothing pinned, and the app list they also refresh
     *  already re-runs the home state. */
    @Synchronized
    fun invalidate(packageName: String) {
        if (drop(packageName)) _generation.update { it + 1 }
    }

    /**
     * The system reports that [owners]' shortcuts changed. Those with a shortcut on home ([pinned])
     * lose their entries, and home resolves them afresh even when there was nothing to drop: a row
     * whose last resolution failed has no entry, and nothing but a new pass can bring its icon
     * back. Returns those owners, for the caller's check of their rows; the rest (chat apps
     * re-publishing on every message, nothing of theirs on home) cost nothing.
     */
    @Synchronized
    fun shortcutsChanged(owners: Set<Pair<String, Long>>, pinned: Set<Pair<String, Long>>): Set<Pair<String, Long>> {
        val onHome = owners.filterTo(HashSet()) { it in pinned }
        if (onHome.isEmpty()) return onHome
        onHome.forEach { drop(it.first) }
        _generation.update { it + 1 }
        return onHome
    }

    /** Ends [packageName]'s epoch and removes its entries; true if there were any. */
    private fun drop(packageName: String): Boolean {
        epochs[packageName] = epoch(packageName) + 1
        return entries.keys.removeAll { it.packageName == packageName }
    }
}
