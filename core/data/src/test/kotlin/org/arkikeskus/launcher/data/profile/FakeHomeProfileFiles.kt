package org.arkikeskus.launcher.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach

/** An in-memory preferences store that counts its writes. */
class CountingStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    var writes = 0
        private set

    /** Runs after each write, with what the store then holds: the way to do something between two writes
     *  of someone's work. */
    var afterWrite: ((Preferences) -> Unit)? = null

    /** Runs once, as the next reader is handed what the store holds: the way to change something between
     *  two reads of someone's work (the reader still gets what it was being handed). */
    var onNextRead: (suspend () -> Unit)? = null

    /** While set, every write fails with it, as a full disk makes it fail. */
    var failWrites: Throwable? = null

    /** Set to have the NEXT write refused after its change has been worked out: this runs in the moment
     *  between the two, then the write fails as a full disk makes it fail, and the store is as it was. */
    var refuseNextWrite: (suspend () -> Unit)? = null

    override val data: Flow<Preferences> = state.onEach { onNextRead?.also { onNextRead = null }?.invoke() }

    val now: Preferences get() = state.value

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        failWrites?.let { throw it }
        refuseNextWrite?.also { refuseNextWrite = null }?.let { between ->
            transform(state.value)
            between()
            throw java.io.IOException("No space left on device")
        }
        writes++
        return transform(state.value).also {
            state.value = it
            afterWrite?.invoke(it)
        }
    }
}

/** The further profiles' stores, in memory: one per id, and the ids whose store was deleted. */
class FakeHomeProfileFiles : HomeProfileFiles {
    val stores = HashMap<Long, CountingStore>()
    val deleted = mutableListOf<Long>()

    /** While set, no store can be removed: every [delete] fails with it. */
    var failDelete: Throwable? = null

    override fun store(profileId: Long): CountingStore = stores.getOrPut(profileId) { CountingStore() }

    /** While set, the stores cannot be listed: every [ids] fails with it. */
    var failIds: Throwable? = null

    override suspend fun ids(): List<Long> {
        failIds?.let { throw it }
        return stores.keys.sorted()
    }

    override suspend fun delete(profileId: Long) {
        failDelete?.let { throw it }
        stores.remove(profileId)
        deleted += profileId
    }
}
