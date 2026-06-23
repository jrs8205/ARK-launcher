package org.arkikeskus.launcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeItemDao {
    @Query("SELECT * FROM home_items ORDER BY position ASC")
    fun observeAll(): Flow<List<HomeItemEntity>>

    @Insert
    suspend fun insert(item: HomeItemEntity)

    @Query("DELETE FROM home_items WHERE packageName = :pkg AND className = :cls AND userSerial = :user")
    suspend fun deleteByKey(pkg: String, cls: String, user: Long)

    @Query("SELECT COUNT(*) FROM home_items WHERE packageName = :pkg AND className = :cls AND userSerial = :user")
    suspend fun count(pkg: String, cls: String, user: Long): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM home_items")
    suspend fun maxPosition(): Int
}
