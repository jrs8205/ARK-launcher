package org.arkikeskus.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Seeds a new installation before the first settings read or write. Existing profiles keep their defaults. */
class NewInstallPreferences(
    private val scope: CoroutineScope,
    private val isFresh: () -> Boolean,
    private val open: (CoroutineScope) -> DataStore<Preferences>,
) : DataStore<Preferences> {
    private val mutex = Mutex()
    @Volatile private var ready: DataStore<Preferences>? = null

    private suspend fun initialized(): DataStore<Preferences> = ready ?: mutex.withLock {
        ready ?: openInitialized().also { ready = it }
    }

    private suspend fun openInitialized(): DataStore<Preferences> {
        if (!withContext(Dispatchers.IO) { isFresh() }) return open(scope)
        val lifetime = SupervisorJob(scope.coroutineContext[Job])
        var committed = false
        try {
            val candidate = open(CoroutineScope(scope.coroutineContext + lifetime))
            candidate.edit { if (it.asMap().isEmpty()) it[SettingsRepository.AUTOMATIC_HOME_LABELS] = -1 }
            committed = true
            return candidate
        } catch (_: IOException) {
            // A failed file replacement can leave the proposed value in DataStore's cache.
            // Release that store before reading the committed value or accepting the next user write.
        } finally {
            if (!committed) withContext(NonCancellable) { lifetime.cancelAndJoin() }
        }
        return open(scope)
    }

    override val data = flow { emitAll(initialized().data) }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        initialized().updateData(transform)
}
