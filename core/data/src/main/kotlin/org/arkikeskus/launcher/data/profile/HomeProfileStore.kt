package org.arkikeskus.launcher.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.arkikeskus.launcher.data.profile.ProfiledPreferences.Companion.copy
import org.arkikeskus.launcher.model.HomeProfile

/** Which home profile is on. The layout and the settings both go by this one value. */
interface ActiveHomeProfile {
    val id: Flow<Long>

    suspend fun current(): Long

    /** Runs [work], told which profile is on, while that profile stays on: a switch asked for meanwhile waits.
     *  Where there is one profile and no switching, [work] simply runs. */
    suspend fun <T> whileActiveStays(work: suspend (active: Long) -> T): T = work(current())
}

/** A launcher with the first profile only: what there was before profiles, and what a test that does not
 *  care about profiles gets. */
object FirstHomeProfile : ActiveHomeProfile {
    override val id: Flow<Long> = flowOf(HomeProfile.FIRST_ID)

    override suspend fun current(): Long = HomeProfile.FIRST_ID
}

/**
 * The list of home profiles and which one is on, kept in the settings file [main] under the reserved keys
 * ([HomeProfileKeys.RESERVED]). Nothing stored is one unnamed first profile, on: the launcher as it was
 * before profiles. Every write here holds [view]'s mutex, as every write through the view does.
 *
 * This class knows a profile's settings only. Its layout rows, pictures and pinned shortcuts are the
 * business of whoever calls it.
 */
class HomeProfileStore(
    private val main: DataStore<Preferences>,
    private val view: ProfiledPreferences,
    private val files: HomeProfileFiles,
) : ActiveHomeProfile {

    val profiles: Flow<List<HomeProfile>> = main.data.map(::profilesIn).distinctUntilChanged()

    override val id: Flow<Long> = main.data.map { ProfiledPreferences.activeId(it) }.distinctUntilChanged()

    override suspend fun current(): Long = ProfiledPreferences.activeId(main.data.first())

    suspend fun list(): List<HomeProfile> = profilesIn(main.data.first())

    /**
     * A new profile after the others, with a copy of [copyOf]'s own values or with none: [prepare] and
     * [publish] in one, for a caller that has nothing else to make of it. Null when the list is full. It is
     * not switched to.
     */
    suspend fun create(name: String, copyOf: Long?): HomeProfile? =
        prepare(name, copyOf)?.takeIf { publish(it) }

    /**
     * The first half of [create]: the profile a new one will be, with its own values written (a copy of
     * [copyOf]'s or none), and NOT in the list yet. Null when the list is full. The caller makes the rest of
     * it (its rows, its pictures) and then lists it ([publish]), or gives it up ([discard]): a profile is
     * listed when it is whole, so a process that dies half-way, or a write that fails, leaves no profile that
     * shows its source's look over an empty home screen. Its id is given again until a profile is listed
     * with it, so whoever makes the rest first clears what an earlier attempt left under that id.
     *
     * The id is one that NOTHING IS KEPT UNDER, or only what an attempt that died left ([freeId]). [kept] are
     * the ids the caller knows something is kept under outside the settings (layout rows, pictures). The
     * profile's own store says that it is being made ([HomeProfileKeys.BEING_MADE]) until [publish].
     *
     * A write that fails (a full disk) is thrown to the caller, with what it may have left of the file taken
     * away first: there is no profile to give up ([discard]) when this did not return one.
     */
    suspend fun prepare(name: String, copyOf: Long?, kept: Set<Long> = emptySet()): HomeProfile? = view.mutex.withLock {
        val now = main.data.first()
        val list = profilesIn(now)
        if (list.size >= HomeProfile.MAX_PROFILES) return@withLock null
        val profile = HomeProfile(freeId(now, list, kept), HomeProfile.cleanName(name))
        // Known as being made BEFORE its values are written: from that write on they name pictures (a copy's
        // folders show its source's), and whoever asks which pictures are in use must find them named.
        view.making(profile.id)
        try {
            files.store(profile.id).updateData {
                valuesFor(copyOf, list).toMutablePreferences().apply { this[BEING_MADE] = true }.toPreferences()
            }
        } catch (e: Exception) {
            view.made(profile.id)
            dropFile(profile.id)
            throw e
        }
        profile
    }

    /** Lists [profile], made with [prepare] and whole now; false when the list filled up meanwhile. */
    suspend fun publish(profile: HomeProfile): Boolean = view.mutex.withLock {
        val now = main.data.first()
        val list = profilesIn(now)
        if (list.size >= HomeProfile.MAX_PROFILES || list.any { it.id == profile.id }) return@withLock false
        // Whole now, and said so in its own store BEFORE it is listed: listed first, a profile whose mark was
        // never taken out would look like an attempt that died to whoever reads its store after the list was
        // lost, and be cleared. (Dying between the two leaves it unlisted and unmarked: kept, never taken.)
        files.store(profile.id).updateData { it.toMutablePreferences().apply { remove(BEING_MADE) }.toPreferences() }
        main.edit {
            it[LIST] = HomeProfile.encode(list + profile)
            it[LAST_ID] = maxOf(ProfiledPreferences.lastId(now), profile.id)
        }
        // Listed: from here the list itself says that its values count.
        view.made(profile.id)
        true
    }

    /** Gives up a profile made with [prepare] and never listed: its own values go. */
    suspend fun discard(profile: HomeProfile) = view.mutex.withLock {
        view.made(profile.id)
        if (profilesIn(main.data.first()).none { it.id == profile.id }) dropFile(profile.id)
    }

    /**
     * The id a new profile gets: the first one after every id the list has and remembers having given, that
     * nothing is kept under. The list and its memory are in the settings file, and a settings file that was
     * damaged reads as empty: one unnamed first profile, no memory of any id. The other profiles' own stores,
     * their layout rows and their pictures are elsewhere and are still there. Counted from the list alone,
     * the next profile got the id of one of them, its values were written over that profile's, and its
     * maker cleared that profile's home screen as if it were its own left-overs.
     *
     * So an id is passed over when anything is kept under it: values in its own store, or what the caller
     * knows of ([kept]: layout rows, pictures). The one exception is an id whose store says it was being made
     * ([HomeProfileKeys.BEING_MADE]): that is an attempt that died half-way, never a profile anyone used, and
     * it is given again for its maker to clear.
     */
    private suspend fun freeId(now: Preferences, list: List<HomeProfile>, kept: Set<Long>): Long {
        var id = maxOf(ProfiledPreferences.lastId(now), list.maxOf { it.id }) + 1
        while (isKeptUnder(id, kept)) id++
        return id
    }

    private suspend fun isKeptUnder(id: Long, kept: Set<Long>): Boolean {
        val own = files.store(id).data.first()
        if (own[BEING_MADE] == true) return false
        return id in kept || own.asMap().isNotEmpty()
    }

    /** A new profile's own values: [copyOf]'s without its bookkeeping ([HomeProfileKeys.NOT_COPIED]), or none. */
    private suspend fun valuesFor(copyOf: Long?, list: List<HomeProfile>): Preferences =
        if (copyOf != null && list.any { it.id == copyOf }) {
            mutablePreferencesOf().apply { copy(view.profileValues(copyOf)) { it !in HomeProfileKeys.NOT_COPIED } }
        } else {
            emptyPreferences()
        }

    suspend fun rename(id: Long, name: String) = editList { list ->
        list.map { if (it.id == id) it.copy(name = HomeProfile.cleanName(name)) else it }
    }

    /** Replaces the profile that has [profile]'s id (its rule, its schedule, its name); no such profile, no change. */
    suspend fun update(profile: HomeProfile) = editList { list ->
        list.map { if (it.id == profile.id) profile.copy(name = HomeProfile.cleanName(profile.name)) else it }
    }

    /** Moves [id] by [by] places in the list, as far as the list goes. */
    suspend fun move(id: Long, by: Int) = editList { list ->
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return@editList list
        val to = (from + by).coerceIn(0, list.lastIndex)
        list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Held by a switch, and by any work that is about "the profile that is on" from its first step to its
     * last: a restore (its settings, its rows and the list it leaves), an export, making a copy, deleting a
     * profile, a change of the grid with the rows it moves, another app's pin request from its last decision to
     * its row. Without it a tap on another profile in the
     * middle put the rows in one profile and the rest in another. Taken before [ShortcutPinLock] by
     * whoever needs both; never the other way round.
     */
    private val steady = Mutex()

    /**
     * Runs [work] with the profile that is on, which stays on until [work] returns: a switch asked for
     * meanwhile waits and happens after it. Not re-entrant: inside, switch with [setActiveLocked].
     */
    override suspend fun <T> whileActiveStays(work: suspend (active: Long) -> T): T = steady.withLock { work(current()) }

    /**
     * Switches to [id]; an id that is not in the list is ignored. Waits for work that needs the profile to
     * stay ([whileActiveStays]).
     *
     * The profile's own values are read BEFORE it is said to be on, and what that reading throws (a store
     * that cannot be read) is thrown from here with nothing changed. Which profile is on decides where every
     * reader of the settings reads from, at the next start too: said to be on unread, a profile whose store
     * failed stopped the home screen from coming up, and Settings with it, where the switch is taken back.
     */
    suspend fun setActive(id: Long) = steady.withLock { setActiveLocked(id) }

    /** [setActive] for a caller inside [whileActiveStays]. */
    internal suspend fun setActiveLocked(id: Long) = view.mutex.withLock {
        val now = main.data.first()
        if (profilesIn(now).none { it.id == id } || ProfiledPreferences.activeId(now) == id) return@withLock
        view.profileValues(id)
        main.edit { it[ACTIVE] = id }
    }

    /**
     * Removes [id] and its own values. Refused (false) for the last profile, for the one that is on (switch
     * first) and for one that is not in the list.
     */
    suspend fun delete(id: Long): Boolean = view.mutex.withLock {
        val now = main.data.first()
        val list = profilesIn(now)
        val active = ProfiledPreferences.activeId(now)
        if (list.size <= 1 || id == active || list.none { it.id == id }) return@withLock false
        val rest = list.filterNot { it.id == id }
        if (id == ProfiledPreferences.mainStoreId(now)) {
            moveIntoMain(active, rest)
        } else {
            main.edit { it[LIST] = HomeProfile.encode(rest) }
        }
        dropFile(id)
        true
    }

    /**
     * Removes the store file of [id], a profile the list no longer has. The list is what says which profiles
     * there are, so the profile is gone whether or not its file can be removed: a file left behind is no
     * profile's any more (nothing shows it, and its id is not given again), and failing here would report a
     * removal that happened as one that did not. What such a file names is still counted as in use
     * ([ProfiledPreferences.valuesOfEveryProfile] cannot tell it from the store of a profile whose listing
     * was lost): its folders' pictures stay on the disk for as long as the file does.
     */
    private suspend fun dropFile(id: Long) {
        try {
            files.delete(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Leaves the profile that is on as the only one: the others' own values go. Returns the ids removed.
     * A restore replaces everything, and what it restores is one profile.
     */
    suspend fun keepOnlyActive(): List<Long> = view.mutex.withLock {
        val now = main.data.first()
        val list = profilesIn(now)
        val active = ProfiledPreferences.activeId(now)
        val kept = list.filter { it.id == active }
        val removed = list.filterNot { it.id == active }.map { it.id }
        if (removed.isEmpty() || kept.isEmpty()) return@withLock emptyList()
        if (active == ProfiledPreferences.mainStoreId(now)) {
            main.edit { it[LIST] = HomeProfile.encode(kept) }
        } else {
            moveIntoMain(active, kept)
        }
        for (id in removed) dropFile(id)
        removed
    }

    /**
     * The ids an own store is kept for though the list does not name them: of a profile whose listing was
     * lost, of an attempt at a profile that died, of a profile whose store could not be removed. Throws when
     * the stores cannot be listed ([HomeProfileFiles.ids]).
     */
    suspend fun unlistedStores(): Set<Long> = view.mutex.withLock {
        val listed = profilesIn(main.data.first()).mapTo(HashSet()) { it.id }
        files.ids().filterTo(HashSet()) { it !in listed }
    }

    /**
     * Drops the own stores of [ids] that the list does not name: profiles whose listing was lost with a
     * damaged settings file, and whose home screens a restore has just replaced with the user's word. A
     * listed profile is left alone.
     */
    suspend fun forgetUnlisted(ids: Set<Long>) = view.mutex.withLock {
        val listed = profilesIn(main.data.first()).mapTo(HashSet()) { it.id }
        for (id in ids) if (id !in listed) dropFile(id)
    }

    /**
     * Makes [profileId], whose own values are in a store of its own, the profile that lives in the settings
     * file: in ONE write its values replace the per-profile values there (those of a profile that is going)
     * and the list becomes [list]. Only then is its own store dropped; if that never happens the left-over
     * store is never read, since the settings file now says where the values are. So a store that cannot be
     * removed fails nothing ([dropFile]): thrown from here, a deletion that had happened was reported as one
     * that had not, and a restore lost the list of the profiles it had removed (their pictures stayed).
     */
    private suspend fun moveIntoMain(profileId: Long, list: List<HomeProfile>) {
        val own = view.profileValues(profileId)
        main.edit { p ->
            p.removePerProfile()
            p.copy(own) { true }
            p[MAIN_STORE] = profileId
            p[LIST] = HomeProfile.encode(list)
        }
        dropFile(profileId)
    }

    private fun MutablePreferences.removePerProfile() {
        for (key in asMap().keys.filter { HomeProfileKeys.isPerProfile(it.name) }) remove(key)
    }

    private suspend fun editList(change: (List<HomeProfile>) -> List<HomeProfile>) = view.mutex.withLock {
        val list = profilesIn(main.data.first())
        val next = change(list)
        if (next != list) main.edit { it[LIST] = HomeProfile.encode(next) }
    }

    private fun profilesIn(values: Preferences): List<HomeProfile> = ProfiledPreferences.listed(values)

    private companion object {
        val ACTIVE = longPreferencesKey(HomeProfileKeys.ACTIVE)
        val MAIN_STORE = longPreferencesKey(HomeProfileKeys.MAIN_STORE)
        val LIST = stringPreferencesKey(HomeProfileKeys.LIST)
        val LAST_ID = longPreferencesKey(HomeProfileKeys.LAST_ID)
        val BEING_MADE = booleanPreferencesKey(HomeProfileKeys.BEING_MADE)
    }
}
