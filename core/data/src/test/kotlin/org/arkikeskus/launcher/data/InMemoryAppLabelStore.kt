package org.arkikeskus.launcher.data

/** The persisted labels of a test: a map per locale, and a count of the writes made to it. */
class InMemoryAppLabelStore : AppLabelStore {
    val stored = HashMap<String, Map<String, String>>()
    var writes = 0

    override suspend fun read(locale: String): Map<String, String> = stored[locale].orEmpty()

    override suspend fun write(locale: String, labels: Map<String, String>) {
        stored[locale] = labels.toMap()
        writes++
    }
}
