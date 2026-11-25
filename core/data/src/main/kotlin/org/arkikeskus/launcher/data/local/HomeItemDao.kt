package org.arkikeskus.launcher.data.local

import kotlinx.coroutines.flow.Flow

/**
 * The home layout rows of ONE home profile: the profile that is on, or the one a caller named around its
 * work with [inHomeProfile]. Everything here, the lookups and changes by row id included, sees and touches
 * that profile's rows only; the few members that reach every profile say so in their names.
 *
 * [ProfileHomeItemDao] implements it over the table ([HomeItemRoomDao]).
 */
interface HomeItemDao {
    /** The home profile the calls made here go to right now. */
    suspend fun resolvedProfile(): Long

    /** Runs [work], told which profile is on, while that profile stays on ([ActiveHomeProfile.whileActiveStays]). */
    suspend fun <T> whileActiveStays(work: suspend (active: Long) -> T): T

    /** Every row (home items, folders, and folder children) — the ViewModel partitions by container. */
    fun observeAll(): Flow<List<HomeItemEntity>>

    /** [observeAll] with the profile the rows belong to, so that an empty layout still names its profile. */
    fun observeAllWithProfile(): Flow<Pair<Long, List<HomeItemEntity>>>

    suspend fun getContainerOrdered(containerId: Long): List<HomeItemEntity>

    suspend fun getContainer(containerId: Long): List<HomeItemEntity>

    /** Every row in both containers, for the ghost-row sweep. */
    suspend fun getAll(): List<HomeItemEntity>

    suspend fun getById(id: Long): HomeItemEntity?

    suspend fun getByKey(containerId: Long, pkg: String, cls: String, user: Long): HomeItemEntity?

    suspend fun getAt(containerId: Long, page: Int, cellX: Int, cellY: Int): HomeItemEntity?

    /** Stores [item] in the resolved profile, whatever profile the row itself names. */
    suspend fun insert(item: HomeItemEntity): Long

    suspend fun deleteById(id: Long)

    suspend fun deleteByKey(containerId: Long, pkg: String, cls: String, user: Long)

    suspend fun clear()

    suspend fun count(containerId: Long, pkg: String, cls: String, user: Long): Int

    suspend fun childCount(containerId: Long): Int

    suspend fun moveById(id: Long, containerId: Long, page: Int, cellX: Int, cellY: Int)

    /** Shifts the page of every row in [containerId] at or after [from] by [offset]. */
    suspend fun offsetPages(containerId: Long, from: Int, offset: Int)

    suspend fun countOnPage(containerId: Long, page: Int): Int

    /** Ids of the folder rows on [page] of [containerId]; their children live under those ids. */
    suspend fun folderIdsOnPage(containerId: Long, page: Int): List<Long>

    suspend fun deleteByContainers(containerIds: List<Long>)

    suspend fun deleteOnPage(containerId: Long, page: Int)

    /** Points an app row at another launcher activity of the same package (see the ghost sweep). */
    suspend fun updateClassName(id: Long, className: String)

    suspend fun renameFolder(id: Long, name: String)

    suspend fun updateSpans(id: Long, spanX: Int, spanY: Int)

    /** Binds a restored (placeholder) widget row to its freshly allocated device-local [appWidgetId]. */
    suspend fun updateWidgetId(id: Long, appWidgetId: Int)

    /** Unbinds a widget row (stale id from another device's restore) back into a placeholder. */
    suspend fun clearWidgetId(id: Long)

    /** The device-local ids of the widgets bound in the resolved profile. */
    suspend fun boundWidgetIds(): List<Int>

    suspend fun getAllOnce(): List<HomeItemEntity>

    /** Atomically replaces the layout of the resolved profile (wipes its rows incl. widgets, inserts [items]). */
    suspend fun replaceLayout(items: List<HomeItemEntity>)

    // --- What must see every home profile ---

    /** The widget ids the rows of EVERY profile hold, to reconcile against the AppWidgetHost: a profile
     *  that is not showing still owns its widgets. */
    suspend fun boundWidgetIdsOfEveryProfile(): List<Int>

    /** The pinned shortcuts of a package in EVERY profile: the system keeps one pinned set per package. */
    suspend fun shortcutIdsOfEveryProfile(pkg: String, user: Long): List<String>

    /** The apps with a shortcut pinned on the home screen of ANY profile, followed as the rows change. */
    fun observeShortcutOwnersOfEveryProfile(): Flow<List<ShortcutOwner>>

    suspend fun getAllOfEveryProfile(): List<HomeItemEntity>

    /** The home profiles that have a row at all. */
    suspend fun profileIds(): List<Long>

    /** [profileIds], followed as the rows change. */
    fun observeProfileIds(): Flow<List<Long>>

    /** Removes every row of [profile], whichever profile is resolved. */
    suspend fun deleteProfile(profile: Long)

    suspend fun deleteBuiltinsOfType(type: String)

    /**
     * Atomically replaces the rows of EVERY home profile with [items], which become the resolved
     * profile's: what a restore does. In one transaction, because the rows come with the ids their
     * backup gave them, and an id still held by a row of another profile would collide.
     */
    suspend fun replaceEveryProfile(items: List<HomeItemEntity>)
}
