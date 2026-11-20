package org.arkikeskus.launcher.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
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
     * A new profile after the others, with a copy of [copyOf]'s own values or with none. Null when the
     * list is full. It is not switched to.
     */
    suspend fun create(name: String, copyOf: Long?): HomeProfile? = view.mutex.withLock {
        val now = main.data.first()
        val list = profilesIn(now)
        if (list.size >= HomeProfile.MAX_PROFILES) return@withLock null
        val id = maxOf(now[LAST_ID] ?: 0L, list.maxOf { it.id }) + 1
        val profile = HomeProfile(id, HomeProfile.cleanName(name))
        // Its values before its place in the list: a profile that is listed always has what it shows. An
        // empty one's file is written too: a copy that died between its values and its place in the list
        // left a file under this very id, which an empty profile would otherwise show as its own.
        val source = if (copyOf != null && list.any { it.id == copyOf }) view.profileValues(copyOf) else emptyPreferences()
        files.store(id).updateData { source }
        main.edit {
            it[LIST] = HomeProfile.encode(list + profile)
            it[LAST_ID] = id
        }
        profile
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

    /** Switches to [id]; an id that is not in the list is ignored. Waits for work that needs the profile to
     *  stay ([whileActiveStays]). */
    suspend fun setActive(id: Long) = steady.withLock { setActiveLocked(id) }

    /** [setActive] for a caller inside [whileActiveStays]. */
    internal suspend fun setActiveLocked(id: Long) = view.mutex.withLock {
        val now = main.data.first()
        if (profilesIn(now).none { it.id == id } || ProfiledPreferences.activeId(now) == id) return@withLock
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
     * there are, so the profile is gone whether or not its file can be removed: a file left behind is never
     * read again (ids are not reused), and failing here would report a removal that happened as one that did not.
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
     * Makes [profileId], whose own values are in a store of its own, the profile that lives in the settings
     * file: in ONE write its values replace the per-profile values there (those of a profile that is going)
     * and the list becomes [list]. Only then is its own store dropped; if that never happens the left-over
     * store is never read, since the settings file now says where the values are.
     */
    private suspend fun moveIntoMain(profileId: Long, list: List<HomeProfile>) {
        val own = view.profileValues(profileId)
        main.edit { p ->
            p.removePerProfile()
            p.copy(own) { true }
            p[MAIN_STORE] = profileId
            p[LIST] = HomeProfile.encode(list)
        }
        files.delete(profileId)
    }

    private fun MutablePreferences.removePerProfile() {
        for (key in asMap().keys.filter { HomeProfileKeys.isPerProfile(it.name) }) remove(key)
    }

    private suspend fun editList(change: (List<HomeProfile>) -> List<HomeProfile>) = view.mutex.withLock {
        val list = profilesIn(main.data.first())
        val next = change(list)
        if (next != list) main.edit { it[LIST] = HomeProfile.encode(next) }
    }

    private fun profilesIn(values: Preferences): List<HomeProfile> = HomeProfile.decode(values[LIST])

    private companion object {
        val ACTIVE = longPreferencesKey(HomeProfileKeys.ACTIVE)
        val MAIN_STORE = longPreferencesKey(HomeProfileKeys.MAIN_STORE)
        val LIST = stringPreferencesKey(HomeProfileKeys.LIST)
        val LAST_ID = longPreferencesKey(HomeProfileKeys.LAST_ID)
    }
}
