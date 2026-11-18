package org.arkikeskus.launcher.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.arkikeskus.launcher.model.HomeProfile

/**
 * The settings store the launcher reads and writes: the values every home profile shares, from the
 * settings file [main], together with the own values of the profile that is on.
 *
 * One profile keeps its own values in [main] itself ([HomeProfileKeys.MAIN_STORE]; the first profile until
 * it is deleted), every other one in a store of [files]. While the profile in [main] is the one that is
 * on, this view IS [main]: a launcher with one profile reads and writes the file it always has, key for
 * key. While another is on, [main]'s per-profile values belong to a profile that is not showing: they are
 * neither read nor written, whatever is done through the view (a restore clears it whole).
 *
 * The profile bookkeeping ([HomeProfileKeys.RESERVED]) shows in the view and cannot be changed through it.
 */
class ProfiledPreferences(
    private val main: DataStore<Preferences>,
    private val files: HomeProfileFiles,
) : DataStore<Preferences> {

    /** Held over every write, here and by the profile list's own writes to [main]. */
    val mutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val data: Flow<Preferences> = main.data
        .flatMapLatest { shared ->
            val active = activeId(shared)
            if (active == mainStoreId(shared)) flowOf(shared) else files.store(active).data.map { own -> viewOf(shared, own) }
        }
        .distinctUntilChanged()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        val shared = main.data.first()
        val active = activeId(shared)
        if (active == mainStoreId(shared)) {
            val next = withReservedOf(shared, transform(shared))
            if (next != shared) main.updateData { next }
            return@withLock next
        }
        val store = files.store(active)
        val own = store.data.first()
        val next = transform(viewOf(shared, own))
        // The shared part goes to the settings file, beside the main-store profile's own values and
        // the bookkeeping, both as they were; the rest is the active profile's.
        val nextShared = mutablePreferencesOf().apply {
            copy(shared) { isPerProfile(it) || it in HomeProfileKeys.RESERVED }
            copy(next) { !isPerProfile(it) && it !in HomeProfileKeys.RESERVED }
        }.toPreferences()
        val nextOwn = mutablePreferencesOf().apply { copy(next, ::isPerProfile) }.toPreferences()
        if (nextShared != shared) main.updateData { nextShared }
        if (nextOwn != perProfileOf(own)) store.updateData { nextOwn }
        viewOf(nextShared, nextOwn)
    }

    /** The own values of [profileId] alone, whether it is on or not. */
    suspend fun profileValues(profileId: Long): Preferences {
        val shared = main.data.first()
        return perProfileOf(if (profileId == mainStoreId(shared)) shared else files.store(profileId).data.first())
    }

    /** Runs [transform] on the own values of [profileId] alone, whether it is on or not; anything it sets
     *  that is not a profile's own is dropped. */
    suspend fun updateProfile(profileId: Long, transform: (MutablePreferences) -> Unit) = mutex.withLock {
        updateProfileLocked(profileId, transform)
    }

    /** [updateProfile] for a caller that holds [mutex] already. */
    internal suspend fun updateProfileLocked(profileId: Long, transform: (MutablePreferences) -> Unit) {
        val store = if (profileId == mainStoreId(main.data.first())) main else files.store(profileId)
        store.updateData { current ->
            val own = perProfileOf(current).toMutablePreferences().also(transform)
            mutablePreferencesOf().apply {
                copy(current) { !isPerProfile(it) }
                copy(own, ::isPerProfile)
            }.toPreferences()
        }
    }

    private fun isPerProfile(name: String) = HomeProfileKeys.isPerProfile(name)

    private fun perProfileOf(values: Preferences): Preferences =
        mutablePreferencesOf().apply { copy(values, ::isPerProfile) }.toPreferences()

    /** What is seen while a profile outside [main] is on: [shared] without its per-profile values, with [own]'s. */
    private fun viewOf(shared: Preferences, own: Preferences): Preferences = mutablePreferencesOf().apply {
        copy(shared) { !isPerProfile(it) }
        copy(own, ::isPerProfile)
    }.toPreferences()

    /** [next] with the bookkeeping exactly as [before] has it. */
    private fun withReservedOf(before: Preferences, next: Preferences): Preferences = mutablePreferencesOf().apply {
        copy(next) { it !in HomeProfileKeys.RESERVED }
        copy(before) { it in HomeProfileKeys.RESERVED }
    }.toPreferences()

    companion object {
        private val ACTIVE = longPreferencesKey(HomeProfileKeys.ACTIVE)
        private val MAIN_STORE = longPreferencesKey(HomeProfileKeys.MAIN_STORE)

        /** The profile that is on according to the settings file's values. */
        fun activeId(shared: Preferences): Long = shared[ACTIVE] ?: HomeProfile.FIRST_ID

        /** The profile whose own values the settings file itself holds. */
        fun mainStoreId(shared: Preferences): Long = shared[MAIN_STORE] ?: HomeProfile.FIRST_ID

        /** Copies into the receiver every value of [from] whose name passes [take]. */
        internal fun MutablePreferences.copy(from: Preferences, take: (String) -> Boolean) {
            for ((key, value) in from.asMap()) {
                if (!take(key.name)) continue
                @Suppress("UNCHECKED_CAST")
                this[key as Preferences.Key<Any>] = value
            }
        }
    }
}
