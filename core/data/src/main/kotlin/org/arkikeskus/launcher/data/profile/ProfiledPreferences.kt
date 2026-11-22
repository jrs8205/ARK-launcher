package org.arkikeskus.launcher.data.profile

import androidx.datastore.core.DataStore
import java.io.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The home profile a change of the settings is FOR, carried by the coroutine that makes the change
 * ([writingForHomeProfile]): the profile whose values a screen showed when the user changed one of them.
 */
class WriteForHomeProfile(val profileId: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WriteForHomeProfile>
}

/**
 * Runs [write], whose changes of the settings are for the home profile [profileId]. What they change of a
 * profile's OWN values (its look, its grids, its drawer folders) is written only while that profile is the one
 * that is on; with another one on, that part is left out, and what every profile shares is written all the
 * same. A screen shows one profile's values, and a change made on it can get its turn after a switch that was
 * waiting: the value the user chose for the profile on screen then went into the profile that had come on.
 */
suspend fun <T> writingForHomeProfile(profileId: Long, write: suspend () -> T): T =
    withContext(WriteForHomeProfile(profileId)) { write() }

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
        // A change made for one profile ([writingForHomeProfile]) changes no other profile's own values:
        // judged here, under the lock a switch is written under, so it is the profile the write lands in.
        val forAnother = currentCoroutineContext()[WriteForHomeProfile]?.let { it.profileId != active } ?: false
        if (active == mainStoreId(shared)) {
            val changed = transform(shared)
            val next = withReservedOf(shared, if (forAnother) withOwnValuesOf(shared, changed) else changed)
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
        val nextOwn = if (forAnother) perProfileOf(own) else mutablePreferencesOf().apply { copy(next, ::isPerProfile) }.toPreferences()
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
     *
     * And after those, of every profile whose own store is still on the disk though the list does not name it
     * ([everyProfile]): the list is in the settings file, and a settings file that was damaged reads as one
     * profile, while the other profiles' stores are still there and still name their folders' pictures. Read
     * from the list alone, those pictures were taken for unused at the next start and deleted.
     */
    suspend fun valuesOfEveryProfile(): List<Preferences> = mutex.withLock {
        val shared = main.data.first()
        valuesOf(everyProfile(shared), shared)
    }

    /**
     * The own values of the profiles a correction reaches ([updateEveryProfile]): every profile, or, while
     * the unlisted stores cannot be listed, those that are named or being made. For a reader that corrects
     * and does not delete: what an app key is to be changed to is looked up from these, at every start of the
     * home screen, and a folder that cannot be read must not stop the start. ([valuesOfEveryProfile] fails
     * then, as it must: what it leaves out would be taken for used by nobody.)
     *
     * A profile whose own store cannot be READ just now is passed over too, one by one: the store of a
     * profile that is not on can fail while the profile that is on is whole, and the keys are read at every
     * start. Its keys are found again, and corrected, at a start when it can be read. Not for a caller that
     * holds [mutex].
     */
    suspend fun valuesOfProfilesToCorrect(): List<Preferences> = mutex.withLock {
        val shared = main.data.first()
        profilesToCorrect(shared).mapNotNull { id ->
            try { valuesOf(listOf(id), shared).single() } catch (e: IOException) { null }
        }
    }

    /** The own values of [ids], each from where it is kept; fails when one of them cannot be read. For a
     *  caller that holds [mutex]. */
    private suspend fun valuesOf(ids: List<Long>, shared: Preferences): List<Preferences> {
        val inMain = mainStoreId(shared)
        return ids.map { id -> perProfileOf(if (id == inMain) shared else files.store(id).data.first()) }
    }

    /** Every profile something of its own is kept for: those the list in [shared] names, in its order, then
     *  those being made, then those whose own store is still there unlisted. Throws when the stores cannot
     *  be listed ([HomeProfileFiles.ids]): a reader that deletes what "no profile" names must not be told
     *  of fewer profiles than there are. For a caller that holds [mutex]. */
    private suspend fun everyProfile(shared: Preferences): List<Long> = (namedOrBeingMade(shared) + files.ids()).distinct()

    /** The profiles the list in [shared] names, in its order, then those being made. */
    private fun namedOrBeingMade(shared: Preferences): List<Long> =
        (listed(shared).map { it.id } + beingMade).distinct()

    /**
     * The profiles a correction is read from and written to: [everyProfile], or [namedOrBeingMade] while
     * the unlisted stores cannot be listed. A correction is not a deletion: the profiles there are still get
     * it, and a store nobody names is shown to nobody meanwhile. One rule for the reader and the writer, so
     * that they keep to one set. For a caller that holds [mutex].
     */
    private suspend fun profilesToCorrect(shared: Preferences): List<Long> =
        try { everyProfile(shared) } catch (e: IOException) { namedOrBeingMade(shared) }

    /**
     * Runs [transform] on the own values of every profile [valuesOfEveryProfile] reads: those the list names
     * and those that are being made ([making]), in one holding of [mutex]. What is corrected in every profile
     * (an app's key that changed) must reach the same profiles a reader of every profile sees: written to the
     * listed ones alone, a copy whose values were already written and which was listed a moment later kept
     * the old key in its folders and its swipe action, while its source had the new one.
     *
     * A profile whose own store cannot be read or written just now is passed over, and the others still get
     * the change ([valuesOfProfilesToCorrect] passes the same one over): what this corrects is found again
     * in that profile at the next start, and corrected then. Not for a caller that holds [mutex].
     */
    suspend fun updateEveryProfile(transform: (MutablePreferences) -> Unit) = mutex.withLock {
        for (id in profilesToCorrect(main.data.first())) {
            try { updateProfileLocked(id, transform) } catch (e: IOException) { /* the next start gets to it */ }
        }
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
        if (listed(main.data.first()).none { it.id == profileId }) return@withLock false
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

    /** [next] with a profile's own values exactly as [before] has them: what every profile shares is [next]'s. */
    private fun withOwnValuesOf(before: Preferences, next: Preferences): Preferences = mutablePreferencesOf().apply {
        copy(next) { !isPerProfile(it) }
        copy(before, ::isPerProfile)
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

        /**
         * What is kept under [name] when it is a [T]; null when nothing is, or when it is of another type.
         *
         * The profile bookkeeping is read through this, never by a typed key alone: the released 1.2.1 kept
         * a key it did not know in the type its backup file gave it, so a settings file that comes from it
         * can hold a text where this version keeps a number (a hand-edited backup restored there). A typed
         * key hands such a value over as it is, and the first use of it throws: for the profile that is on
         * that was the home screen's first reading.
         */
        private inline fun <reified T> Preferences.kept(name: String): T? {
            @Suppress("UNCHECKED_CAST")
            val value: Any? = this[stringPreferencesKey(name) as Preferences.Key<Any>]
            return value as? T
        }

        /** The profiles the list in [shared] names: the one first profile when there is no list, or what is
         *  kept under its name is no list. */
        fun listed(shared: Preferences): List<HomeProfile> = HomeProfile.decode(shared.kept<String>(HomeProfileKeys.LIST))

        /** The profile that is on according to the settings file's values ([listedOrFirst]). */
        fun activeId(shared: Preferences): Long = listedOrFirst(shared, HomeProfileKeys.ACTIVE)

        /** The profile whose own values the settings file itself holds ([listedOrFirst]). */
        fun mainStoreId(shared: Preferences): Long = listedOrFirst(shared, HomeProfileKeys.MAIN_STORE)

        /** The highest profile id ever given, as far as the settings file says (0 = it says nothing). */
        fun lastId(shared: Preferences): Long = shared.kept<Long>(HomeProfileKeys.LAST_ID) ?: 0L

        /**
         * The profile [name] points at when that is a profile of the list; else the first profile (the first
         * of the list when the list has no profile of that id any more). Every state this launcher writes
         * keeps both pointers on listed profiles, so a pointer that names none was not written here: followed
         * all the same, it showed an empty home screen in place of the user's own, or read the settings
         * file's values as nobody's.
         */
        private fun listedOrFirst(shared: Preferences, name: String): Long {
            val ids = listed(shared).map { it.id }
            return shared.kept<Long>(name)?.takeIf { it in ids } ?: ids.firstOrNull { it == HomeProfile.FIRST_ID } ?: ids.first()
        }

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
