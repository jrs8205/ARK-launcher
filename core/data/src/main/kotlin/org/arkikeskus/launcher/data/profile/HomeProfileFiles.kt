package org.arkikeskus.launcher.data.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/**
 * The stores of the home profiles whose own values are not in the settings file itself: one per profile id.
 * The profile that lives in the settings file (see [HomeProfileKeys.MAIN_STORE]) has none.
 */
interface HomeProfileFiles {
    /** The store of [profileId]: the same instance every time, made on first use. */
    fun store(profileId: Long): DataStore<Preferences>

    /** Closes the store of [profileId] and removes what it kept. */
    suspend fun delete(profileId: Long)
}

/** [HomeProfileFiles] on the disk: `home_profile_<id>` beside the settings file. */
class DiskHomeProfileFiles(
    private val context: Context,
    private val scope: CoroutineScope,
) : HomeProfileFiles {
    private class Open(val store: DataStore<Preferences>, val job: Job)

    // A file must have ONE store at a time: a second one on the same file throws on its first read.
    private val open = HashMap<Long, Open>()

    override fun store(profileId: Long): DataStore<Preferences> = synchronized(open) {
        open.getOrPut(profileId) {
            val job = SupervisorJob(scope.coroutineContext[Job])
            Open(
                store = PreferenceDataStoreFactory.create(
                    // As for the settings file: a file an interrupted write broke reads as empty (the
                    // profile falls back to the defaults) instead of throwing on every read.
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO + job),
                    produceFile = { context.preferencesDataStoreFile(fileName(profileId)) },
                ),
                job = job,
            )
        }.store
    }

    override suspend fun delete(profileId: Long) {
        // The store's own scope ends first, so the file is no longer held when it is removed and a
        // later profile of the same id (there is none: ids are not reused) would get a fresh store.
        synchronized(open) { open.remove(profileId) }?.job?.cancelAndJoin()
        withContext(Dispatchers.IO) { context.preferencesDataStoreFile(fileName(profileId)).delete() }
    }

    private fun fileName(profileId: Long) = "home_profile_$profileId"
}
