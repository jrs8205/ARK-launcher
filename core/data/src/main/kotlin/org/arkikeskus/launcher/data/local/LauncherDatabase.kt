package org.arkikeskus.launcher.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

// v3: unique (page, cellX, cellY) index on home_items.
// v4: folders — added containerId + folderName, index now (containerId, page, cellX, cellY).
// v5: pinned deep shortcuts — added nullable shortcutId.
// The DataModule builder uses fallbackToDestructiveMigration, so the table is recreated.
@Database(entities = [HomeItemEntity::class], version = 5, exportSchema = false)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun homeItemDao(): HomeItemDao
}
