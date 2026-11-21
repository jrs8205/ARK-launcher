package org.arkikeskus.launcher.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        val sharedChanged = nextShared != shared
        if (sharedChanged) main.updateData { nextShared }
        if (nextOwn != perProfileOf(own)) {
            try {
                store.updateData { nextOwn }
            } catch (failed: Throwable) {
                // The two files share no transaction. One change is not half saved: what went into the
                // settings file is taken back (nobody else wrote meanwhile, the mutex is held), so a change
                // that cannot be saved changes nothing. A restore that failed here had already changed the
                // press delay and the apps' names for every profile.
                if (sharedChanged) withContext(NonCancellable) { runCatching { main.updateData { shared } } }
                throw failed
            }
        }
        viewOf(nextShared, nextOwn)
    }

    /** The own values of [profileId] alone, whether it is on or not. */
    suspend fun profileValues(profileId: Long): Preferences {
        val shared = main.data.first()
        return perProfileOf(if (profileId == mainStoreId(shared)) shared else files.store(profileId).data.first())
    }

    /** The profiles that are being made ([HomeProfileStore.prepare]): their own values are written, and they
     *  are not in the list yet. In memory only: a process that dies half-way leaves none. */
    private val beingMade = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** [profileId]'s own values are about to be written, before it is listed. For a caller that holds [mutex]. */
    internal fun making(profileId: Long) {
        beingMade += profileId
    }

    /** [profileId] is listed now, or was given up. */
    internal fun made(profileId: Long) {
        beingMade -= profileId
    }

    /**
     * The own values of every profile the list names, in the list's order, and after them of every profile
     * that is being made ([making]), from ONE reading of the settings file and under [mutex]. A copy's values
     * name its source's pictures from the moment they are written, which is before the copy is listed: a
     * picture the source gave up in that moment was taken for unused and deleted, and the copy was listed
     * with a folder that had lost its picture. Where a profile's values are can change: deleting the profile that lives in the
     * settings file moves another one's values into it and drops that one's own store, under this lock. Read
     * one profile at a time without it, a reader looked a profile up in its own store, the delete went by,
     * and the store it then read was gone: no values, and the pictures those values name were taken for unused.
     * Not for a caller that holds [mutex].
     */
    suspend fun valuesOfEveryProfile(): List<Preferences> = mutex.withLock {
        val shared = main.data.first()
        val inMain = mainStoreId(shared)
        val listed = HomeProfile.decode(shared[LIST]).map { it.id }
        (listed + beingMade.filter { it !in listed }).map { id ->
            perProfileOf(if (id == inMain) shared else files.store(id).data.first())
        }
    }

    /**
     * Runs [transform] on the own values of every profile [valuesOfEveryProfile] reads: those the list names
     * and those that are being made ([making]), in one holding of [mutex]. What is corrected in every profile
     * (an app's key that changed) must reach the same profiles a reader of every profile sees: written to the
     * listed ones alone, a copy whose values were already written and which was listed a moment later kept
     * the old key in its folders and its swipe action, while its source had the new one. Not for a caller
     * that holds [mutex].
     */
    suspend fun updateEveryProfile(transform: (MutablePreferences) -> Unit) = mutex.withLock {
        val listed = HomeProfile.decode(main.data.first()[LIST]).map { it.id }
        for (id in listed + beingMade.filter { it !in listed }) updateProfileLocked(id, transform)
    }

    /** Runs [transform] on the own values of [profileId] alone, whether it is on or not; anything it sets
     *  that is not a profile's own is dropped. */
    suspend fun updateProfile(profileId: Long, transform: (MutablePreferences) -> Unit) = mutex.withLock {
        updateProfileLocked(profileId, transform)
    }

    /**
     * [updateProfile] for a profile the list still names: false, with nothing written, when it does not (it
     * was deleted meanwhile). The look at the list and the write are one step under [mutex], which a deletion
     * holds too, so nothing is written for a profile that is gone (that would bring its store file back).
     */
    suspend fun updateListedProfile(profileId: Long, transform: (MutablePreferences) -> Unit): Boolean = mutex.withLock {
        if (HomeProfile.decode(main.data.first()[LIST]).none { it.id == profileId }) return@withLock false
        updateProfileLocked(profileId, transform)
        true
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
        private val LIST = stringPreferencesKey(HomeProfileKeys.LIST)

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
