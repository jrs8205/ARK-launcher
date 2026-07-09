package org.arkikeskus.launcher.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

// v3: unique (page, cellX, cellY) index on home_items.
// v4: folders — added containerId + folderName, index now (containerId, page, cellX, cellY).
// v5: pinned deep shortcuts — added nullable shortcutId.
// v6: widgets — added spanX/spanY/appWidgetId/widgetProvider (multi-cell items).
// v7: built-in widgets — added nullable builtinType (smartspace clock/date/calendar).
// Schema is now EXPORTED to core/data/schemas (room.schemaLocation KSP arg), so each future version
// bump ships a real Migration instead of wiping the layout. DataModule only allows destructive
// fallback FROM the pre-v5 dev versions (1–4, which never had migrations); v5 onward is preserved.
@Database(entities = [HomeItemEntity::class], version = 7, exportSchema = true)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun homeItemDao(): HomeItemDao
}
