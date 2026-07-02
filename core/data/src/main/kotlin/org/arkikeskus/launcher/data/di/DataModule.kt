package org.arkikeskus.launcher.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.arkikeskus.launcher.data.AppIconFetcher
import org.arkikeskus.launcher.data.AppIconKeyer
import org.arkikeskus.launcher.data.LauncherAppsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.LauncherDatabase
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(
    name = "launcher_settings",
    // A corrupted preferences file (interrupted write, power loss) otherwise throws
    // CorruptionException on every read — for the HOME app that's an unrecoverable crash loop.
    // Resetting to defaults loses only settings; the Room-backed home layout is untouched.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE home_items ADD COLUMN spanX INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE home_items ADD COLUMN spanY INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE home_items ADD COLUMN appWidgetId INTEGER")
        db.execSQL("ALTER TABLE home_items ADD COLUMN widgetProvider TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        source: LauncherAppsSource,
    ): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(AppIconKeyer())
            add(AppIconFetcher.Factory(source))
        }
        .build()

    @Provides
    @Singleton
    fun provideLauncherDatabase(@ApplicationContext context: Context): LauncherDatabase =
        Room.databaseBuilder(context, LauncherDatabase::class.java, "launcher.db")
            // The schema is exported (core/data/schemas); a future version bump must add a real
            // Migration here via .addMigrations(...). Destructive fallback is allowed ONLY from the
            // pre-v5 dev versions (1–4, which never shipped a migration) so an old test install resets
            // instead of crashing; v5 onward is migrated, so the user's home layout survives upgrades.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4)
            .addMigrations(MIGRATION_5_6)
            .build()

    @Provides
    fun provideHomeItemDao(database: LauncherDatabase): HomeItemDao = database.homeItemDao()
}
