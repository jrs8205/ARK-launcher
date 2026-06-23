package org.arkikeskus.launcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeItemDao {
    @Query("SELECT * FROM home_items ORDER BY page ASC, cellY ASC, cellX ASC")
    fun observeAll(): Flow<List<HomeItemEntity>>

    @Query("SELECT * FROM home_items")
    suspend fun getAll(): List<HomeItemEntity>

    @Insert
    suspend fun insert(item: HomeItemEntity)

    @Query("DELETE FROM home_items WHERE packageName = :pkg AND className = :cls AND userSerial = :user")
    suspend fun deleteByKey(pkg: String, cls: String, user: Long)

    @Query("SELECT COUNT(*) FROM home_items WHERE packageName = :pkg AND className = :cls AND userSerial = :user")
    suspend fun count(pkg: String, cls: String, user: Long): Int

    @Query(
        "UPDATE home_items SET page = :page, cellX = :cellX, cellY = :cellY " +
            "WHERE packageName = :pkg AND className = :cls AND userSerial = :user",
    )
    suspend fun move(pkg: String, cls: String, user: Long, page: Int, cellX: Int, cellY: Int)
}
