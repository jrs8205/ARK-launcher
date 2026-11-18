package org.arkikeskus.launcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The home layout table as Room reads it: every statement names the home profile it works in, by row id
 * too, so nothing done in one profile can reach a row of another. The layout's code does not use this
 * directly: it goes through [HomeItemDao], which supplies the profile.
 */
@Dao
interface HomeItemRoomDao {
    @Query("SELECT * FROM home_items WHERE homeProfileId = :profile ORDER BY containerId ASC, page ASC, cellY ASC, cellX ASC")
    fun observeAll(profile: Long): Flow<List<HomeItemEntity>>

    @Query(
        "SELECT * FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId " +
            "ORDER BY page ASC, cellY ASC, cellX ASC",
    )
    suspend fun getContainerOrdered(profile: Long, containerId: Long): List<HomeItemEntity>

    @Query("SELECT * FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId")
    suspend fun getContainer(profile: Long, containerId: Long): List<HomeItemEntity>

    @Query("SELECT * FROM home_items WHERE homeProfileId = :profile")
    suspend fun getAll(profile: Long): List<HomeItemEntity>

    @Query("SELECT * FROM home_items WHERE id = :id AND homeProfileId = :profile LIMIT 1")
    suspend fun getById(profile: Long, id: Long): HomeItemEntity?

    @Query(
        "SELECT * FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId " +
            "AND packageName = :pkg AND className = :cls AND userSerial = :user LIMIT 1",
    )
    suspend fun getByKey(profile: Long, containerId: Long, pkg: String, cls: String, user: Long): HomeItemEntity?

    @Query(
        "SELECT * FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId " +
            "AND page = :page AND cellX = :cellX AND cellY = :cellY LIMIT 1",
    )
    suspend fun getAt(profile: Long, containerId: Long, page: Int, cellX: Int, cellY: Int): HomeItemEntity?

    @Insert
    suspend fun insert(item: HomeItemEntity): Long

    @Query("DELETE FROM home_items WHERE id = :id AND homeProfileId = :profile")
    suspend fun deleteById(profile: Long, id: Long)

    @Query(
        "DELETE FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId " +
            "AND packageName = :pkg AND className = :cls AND userSerial = :user",
    )
    suspend fun deleteByKey(profile: Long, containerId: Long, pkg: String, cls: String, user: Long)

    @Query("DELETE FROM home_items WHERE homeProfileId = :profile")
    suspend fun clear(profile: Long)

    @Query(
        "SELECT COUNT(*) FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId " +
            "AND packageName = :pkg AND className = :cls AND userSerial = :user",
    )
    suspend fun count(profile: Long, containerId: Long, pkg: String, cls: String, user: Long): Int

    @Query("SELECT COUNT(*) FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId")
    suspend fun childCount(profile: Long, containerId: Long): Int

    @Query(
        "UPDATE home_items SET containerId = :containerId, page = :page, cellX = :cellX, cellY = :cellY " +
            "WHERE id = :id AND homeProfileId = :profile",
    )
    suspend fun moveById(profile: Long, id: Long, containerId: Long, page: Int, cellX: Int, cellY: Int)

    @Query(
        "UPDATE home_items SET page = page + :offset " +
            "WHERE homeProfileId = :profile AND containerId = :containerId AND page >= :from",
    )
    suspend fun offsetPages(profile: Long, containerId: Long, from: Int, offset: Int)

    @Query("SELECT COUNT(*) FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId AND page = :page")
    suspend fun countOnPage(profile: Long, containerId: Long, page: Int): Int

    @Query(
        "SELECT id FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId " +
            "AND page = :page AND folderName IS NOT NULL",
    )
    suspend fun folderIdsOnPage(profile: Long, containerId: Long, page: Int): List<Long>

    @Query("DELETE FROM home_items WHERE homeProfileId = :profile AND containerId IN (:containerIds)")
    suspend fun deleteByContainers(profile: Long, containerIds: List<Long>)

    @Query("DELETE FROM home_items WHERE homeProfileId = :profile AND containerId = :containerId AND page = :page")
    suspend fun deleteOnPage(profile: Long, containerId: Long, page: Int)

    @Query("UPDATE home_items SET className = :className WHERE id = :id AND homeProfileId = :profile")
    suspend fun updateClassName(profile: Long, id: Long, className: String)

    @Query("UPDATE home_items SET folderName = :name WHERE id = :id AND homeProfileId = :profile")
    suspend fun renameFolder(profile: Long, id: Long, name: String)

    @Query("UPDATE home_items SET spanX = :spanX, spanY = :spanY WHERE id = :id AND homeProfileId = :profile")
    suspend fun updateSpans(profile: Long, id: Long, spanX: Int, spanY: Int)

    @Query("UPDATE home_items SET appWidgetId = :appWidgetId WHERE id = :id AND homeProfileId = :profile")
    suspend fun updateWidgetId(profile: Long, id: Long, appWidgetId: Int)

    @Query("UPDATE home_items SET appWidgetId = NULL WHERE id = :id AND homeProfileId = :profile")
    suspend fun clearWidgetId(profile: Long, id: Long)

    @Query("SELECT appWidgetId FROM home_items WHERE homeProfileId = :profile AND appWidgetId IS NOT NULL")
    suspend fun boundWidgetIds(profile: Long): List<Int>

    /** Replaces the layout of [profile] alone with [items], whatever profile they name. */
    @Transaction
    suspend fun replaceLayout(profile: Long, items: List<HomeItemEntity>) {
        clear(profile)
        for (item in items) insert(item.copy(homeProfileId = profile))
    }

    // --- What must see every home profile ---

    /** The widget ids the rows of EVERY profile hold: a profile that is not showing still owns its widgets. */
    @Query("SELECT appWidgetId FROM home_items WHERE appWidgetId IS NOT NULL")
    suspend fun boundWidgetIdsOfEveryProfile(): List<Int>

    /** The pinned shortcuts of a package on the home screens of EVERY profile (the system keeps one
     *  pinned set per package for the launcher, not one per profile). */
    @Query(
        "SELECT shortcutId FROM home_items WHERE containerId = ${HomeItemEntity.HOME} AND shortcutId IS NOT NULL " +
            "AND packageName = :pkg AND userSerial = :user",
    )
    suspend fun shortcutIdsOfEveryProfile(pkg: String, user: Long): List<String>

    @Query("SELECT * FROM home_items")
    suspend fun getAllOfEveryProfile(): List<HomeItemEntity>

    @Query("SELECT DISTINCT homeProfileId FROM home_items ORDER BY homeProfileId ASC")
    suspend fun profileIds(): List<Long>
}
