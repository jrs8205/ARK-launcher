package org.arkikeskus.launcher.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An in-memory preferences store that counts its writes. */
class CountingStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    var writes = 0
        private set

    override val data: Flow<Preferences> = state.asStateFlow()

    val now: Preferences get() = state.value

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        writes++
        return transform(state.value).also { state.value = it }
    }
}

/** The further profiles' stores, in memory: one per id, and the ids whose store was deleted. */
class FakeHomeProfileFiles : HomeProfileFiles {
    val stores = HashMap<Long, CountingStore>()
    val deleted = mutableListOf<Long>()

    override fun store(profileId: Long): CountingStore = stores.getOrPut(profileId) { CountingStore() }

    override suspend fun delete(profileId: Long) {
        stores.remove(profileId)
        deleted += profileId
    }
}
