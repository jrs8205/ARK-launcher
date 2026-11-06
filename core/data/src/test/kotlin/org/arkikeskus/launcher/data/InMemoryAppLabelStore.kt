package org.arkikeskus.launcher.data

/** The persisted labels of a test: a map per locale, a count of the writes made to it, and a switch
 *  that makes it fail like a full disk (SQLiteFullException is a RuntimeException). */
class InMemoryAppLabelStore : AppLabelStore {
    val stored = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
    @Volatile var writes = 0
    var failWrites = false
    var failReads = false

    override suspend fun read(locale: String): Map<String, String> {
        check(!failReads) { "database unavailable" }
        return stored[locale].orEmpty()
    }

    override suspend fun write(locale: String, labels: Map<String, String>) {
        check(!failWrites) { "database or disk is full" }
        stored[locale] = labels.toMap()
        writes++
    }
}
