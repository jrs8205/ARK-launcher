package org.arkikeskus.launcher.feature.appdrawer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.arkikeskus.launcher.data.profile.HomeProfileFiles
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * A settings store kept in memory, for the tests that are about what is stored and not about the file it is
 * stored in. A real settings file is replaced by a rename at every write, and on Windows that rename fails now
 * and then when it follows another write or a read closely: a test that only asks "which profile's folder
 * changed" then failed for the file system's reasons. One write at a time, as the real store.
 */
internal class InMemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val writes = Mutex()

    /** While set, every write fails with it, as a full disk makes it fail: what is stored stays as it was. */
    @Volatile var failWrites: IOException? = null

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = writes.withLock {
        failWrites?.let { throw it }
        transform(state.value).also { state.value = it }
    }
}

/** The further home profiles' own stores, in memory: one per id. */
internal class InMemoryHomeProfileFiles : HomeProfileFiles {
    private val stores = ConcurrentHashMap<Long, InMemoryPreferences>()

    override fun store(profileId: Long): DataStore<Preferences> = stores.getOrPut(profileId) { InMemoryPreferences() }

    override suspend fun delete(profileId: Long) {
        stores.remove(profileId)
    }

    override suspend fun ids(): List<Long> = stores.keys.sorted()
}
