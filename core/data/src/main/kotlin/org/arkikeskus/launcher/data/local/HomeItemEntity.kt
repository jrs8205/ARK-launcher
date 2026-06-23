package org.arkikeskus.launcher.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A persisted app shortcut placed at a free cell (page, cellX, cellY) on the home screen. */
@Entity(tableName = "home_items")
data class HomeItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val className: String,
    val userSerial: Long,
    val page: Int,
    val cellX: Int,
    val cellY: Int,
) {
    /** Matches AppItem.key so entities can be resolved against the live app list. */
    val key: String get() = "$packageName/$className/$userSerial"
}
