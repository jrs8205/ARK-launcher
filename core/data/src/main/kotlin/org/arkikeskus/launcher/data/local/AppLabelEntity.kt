package org.arkikeskus.launcher.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * One app's label as the last scan read it (see `AppLabelCache`): [key] is `AppItem.key`
 * (`package/class/userSerial`), [locale] the process locale tags the label was read under — a
 * label is localized, so another locale's rows are not this one's.
 */
@Entity(tableName = "app_labels")
data class AppLabelEntity(
    @PrimaryKey val key: String,
    val label: String,
    val locale: String,
)

@Dao
interface AppLabelDao {
    @Query("SELECT * FROM app_labels WHERE locale = :locale")
    suspend fun getAll(locale: String): List<AppLabelEntity>

    @Query("DELETE FROM app_labels")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<AppLabelEntity>)

    /** The whole table becomes [rows]: the current apps of the current locale, nothing stale kept. */
    @Transaction
    suspend fun replaceAll(rows: List<AppLabelEntity>) {
        clear()
        insertAll(rows)
    }
}
