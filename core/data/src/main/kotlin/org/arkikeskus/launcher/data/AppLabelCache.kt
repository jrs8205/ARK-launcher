package org.arkikeskus.launcher.data

import org.arkikeskus.launcher.data.local.AppLabelDao
import org.arkikeskus.launcher.data.local.AppLabelEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Where [AppLabelCache] keeps the labels between process starts: one set per locale. */
interface AppLabelStore {
    /** The labels persisted under [locale], by app key; empty for a locale never persisted. */
    suspend fun read(locale: String): Map<String, String>

    /** Persists [labels] as the whole set of [locale]'s; other locales' sets may be dropped. */
    suspend fun write(locale: String, labels: Map<String, String>)
}

/** The app-label table of the launcher database as the store: one locale's set at a time. */
class RoomAppLabelStore(private val dao: AppLabelDao) : AppLabelStore {
    override suspend fun read(locale: String): Map<String, String> =
        dao.getAll(locale).associate { it.key to it.label }

    override suspend fun write(locale: String, labels: Map<String, String>) =
        dao.replaceAll(labels.map { (key, label) -> AppLabelEntity(key, label, locale) })
}

/**
 * The app labels, kept across process starts — Launcher3's icon cache idea, for the labels alone.
 * Reading a launcher activity's label opens its package's resources, and the app list's first scan
 * read every installed package's: ~0.8 s of a cold start on a phone with ~100 packages, during
 * which the home rows waited for the list. So a scan hands out the label the last scan persisted,
 * at once ([label]), reads only the apps it has no entry for ([put]), and the first scan of a
 * process re-reads the persisted ones in the background ([unverified] → [verify]) to correct a
 * label that changed while the launcher was not running — an update the system never told this
 * process about. A package event forgets that package's entries ([invalidatePackage]) so the next
 * scan reads them; a locale change ([prepare]) starts from that locale's persisted set, a label
 * being read in the process locale. [persist] writes the set back when it changed.
 *
 * Read and written by the app list's one serial scan worker; [invalidatePackage] arrives from the
 * system's callback thread, so every access is synchronized.
 */
@Singleton
class AppLabelCache @Inject constructor(private val store: AppLabelStore) {
    private val lock = Any()
    private var locale: String? = null
    private val labels = HashMap<String, String>()
    /** The keys whose label came from the store and has not been re-read in this process. */
    private val fromStore = HashSet<String>()
    /** What the store holds for [locale] right now, to skip a write that would change nothing. */
    private var persisted: Map<String, String> = emptyMap()

    /** Starts a scan in [locale]: the first call, or a change of locale, loads that locale's set. */
    suspend fun prepare(locale: String) {
        if (synchronized(lock) { this.locale == locale }) return
        val stored = store.read(locale)
        synchronized(lock) {
            this.locale = locale
            labels.clear()
            labels.putAll(stored)
            fromStore.clear()
            fromStore.addAll(stored.keys)
            persisted = stored
        }
    }

    /** The label of [key] as last read or persisted, or null when it must be read now. */
    fun label(key: String): String? = synchronized(lock) { labels[key] }

    /** [label] was read from the app itself just now. */
    fun put(key: String, label: String) = synchronized(lock) {
        labels[key] = label
        fromStore.remove(key)
    }

    /** The keys whose persisted label is still to be checked against the app's own. */
    fun unverified(): Set<String> = synchronized(lock) { fromStore.toSet() }

    /**
     * The labels of (some) [unverified] keys as read from the apps now: each is now this process's
     * reading. Returns true when one differed from what was handed out, so the list is emitted again.
     */
    fun verify(fresh: Map<String, String>): Boolean = synchronized(lock) {
        var changed = false
        for ((key, label) in fresh) {
            if (labels[key] != label) changed = true
            labels[key] = label
            fromStore.remove(key)
        }
        changed
    }

    /** Keeps only the apps in [keys]: the rest were uninstalled (or belong to a profile that left). */
    fun retain(keys: Set<String>) = synchronized(lock) {
        labels.keys.retainAll(keys)
        fromStore.retainAll(keys)
    }

    /** A package was added, changed or removed: its labels are read again at the next scan. */
    fun invalidatePackage(packageName: String) = synchronized(lock) {
        val prefix = "$packageName/"
        labels.keys.removeAll { it.startsWith(prefix) }
        fromStore.removeAll { it.startsWith(prefix) }
    }

    /** Writes the current set to the store, unless the store already holds exactly it. */
    suspend fun persist() {
        val (locale, snapshot) = synchronized(lock) {
            val l = locale ?: return
            if (labels == persisted) return
            l to labels.toMap()
        }
        store.write(locale, snapshot)
        synchronized(lock) { persisted = snapshot }
    }
}
