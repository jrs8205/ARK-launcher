package org.arkikeskus.launcher.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import org.arkikeskus.launcher.data.profile.ActiveHomeProfile
import org.arkikeskus.launcher.data.profile.FirstHomeProfile

// v3: unique (page, cellX, cellY) index on home_items.
// v4: folders — added containerId + folderName, index now (containerId, page, cellX, cellY).
// v5: pinned deep shortcuts — added nullable shortcutId.
// v6: widgets — added spanX/spanY/appWidgetId/widgetProvider (multi-cell items).
// v7: built-in widgets — added nullable builtinType (smartspace clock/date/calendar).
// v8: app labels — the app_labels table (AppLabelEntity), the labels of the last scan per locale.
// v9: home profiles — added homeProfileId (every earlier row is in profile 1), index now
//     (homeProfileId, containerId, page, cellX, cellY).
// Schema is now EXPORTED to core/data/schemas (room.schemaLocation KSP arg), so each future version
// bump ships a real Migration instead of wiping the layout. DataModule only allows destructive
// fallback FROM the pre-v5 dev versions (1–4, which never had migrations); v5 onward is preserved.
@Database(entities = [HomeItemEntity::class, AppLabelEntity::class], version = 9, exportSchema = true)
abstract class LauncherDatabase : RoomDatabase() {
    /** The table itself, every statement naming its home profile. The layout's code uses [homeItemDao]. */
    abstract fun homeItemRoomDao(): HomeItemRoomDao

    abstract fun appLabelDao(): AppLabelDao

    /** The layout rows of the first home profile: the launcher with one profile, and the tests that do
     *  not deal with profiles. Kept without parameters: tests outside this module reach it by name. */
    fun homeItemDao(): HomeItemDao = homeItemDao(FirstHomeProfile)

    /** The layout rows of the home profile [active] says is on. */
    fun homeItemDao(active: ActiveHomeProfile): HomeItemDao = ProfileHomeItemDao(homeItemRoomDao(), active)
}
