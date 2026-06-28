package org.arkikeskus.launcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeItemDao {
    /** Every row (home items, folders, and folder children) — the ViewModel partitions by container. */
    @Query("SELECT * FROM home_items ORDER BY containerId ASC, page ASC, cellY ASC, cellX ASC")
    fun observeAll(): Flow<List<HomeItemEntity>>

    @Query("SELECT * FROM home_items WHERE containerId = :containerId ORDER BY page ASC, cellY ASC, cellX ASC")
    suspend fun getContainerOrdered(containerId: Long): List<HomeItemEntity>

    @Query("SELECT * FROM home_items WHERE containerId = :containerId")
    suspend fun getContainer(containerId: Long): List<HomeItemEntity>

    @Query("SELECT * FROM home_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HomeItemEntity?

    @Query(
        "SELECT * FROM home_items WHERE containerId = :containerId AND packageName = :pkg " +
            "AND className = :cls AND userSerial = :user LIMIT 1",
    )
    suspend fun getByKey(containerId: Long, pkg: String, cls: String, user: Long): HomeItemEntity?

    @Query(
        "SELECT * FROM home_items WHERE containerId = :containerId AND page = :page " +
            "AND cellX = :cellX AND cellY = :cellY LIMIT 1",
    )
    suspend fun getAt(containerId: Long, page: Int, cellX: Int, cellY: Int): HomeItemEntity?

    @Insert
    suspend fun insert(item: HomeItemEntity): Long

    @Query("DELETE FROM home_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "DELETE FROM home_items WHERE containerId = :containerId AND packageName = :pkg " +
            "AND className = :cls AND userSerial = :user",
    )
    suspend fun deleteByKey(containerId: Long, pkg: String, cls: String, user: Long)

    @Query("DELETE FROM home_items")
    suspend fun clear()

    @Query(
        "SELECT COUNT(*) FROM home_items WHERE containerId = :containerId AND packageName = :pkg " +
            "AND className = :cls AND userSerial = :user",
    )
    suspend fun count(containerId: Long, pkg: String, cls: String, user: Long): Int

    @Query("SELECT COUNT(*) FROM home_items WHERE containerId = :containerId")
    suspend fun childCount(containerId: Long): Int

    @Query(
        "UPDATE home_items SET containerId = :containerId, page = :page, cellX = :cellX, cellY = :cellY " +
            "WHERE id = :id",
    )
    suspend fun moveById(id: Long, containerId: Long, page: Int, cellX: Int, cellY: Int)

    @Query("UPDATE home_items SET folderName = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("UPDATE home_items SET spanX = :spanX, spanY = :spanY WHERE id = :id")
    suspend fun updateSpans(id: Long, spanX: Int, spanY: Int)

    @Query("SELECT * FROM home_items")
    suspend fun getAllOnce(): List<HomeItemEntity>

    /** Atomically replaces the entire layout (wipes all rows incl. widgets, inserts [items]). */
    @Transaction
    suspend fun replaceLayout(items: List<HomeItemEntity>) {
        clear()
        for (item in items) insert(item)
    }
}
