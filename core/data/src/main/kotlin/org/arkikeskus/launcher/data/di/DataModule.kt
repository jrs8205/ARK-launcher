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
import org.arkikeskus.launcher.data.AppLabelStore
import org.arkikeskus.launcher.data.AndroidProfileSystem
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.CustomIconFetcher
import org.arkikeskus.launcher.data.CustomIconKeyer
import org.arkikeskus.launcher.data.CustomIconSource
import org.arkikeskus.launcher.data.AndroidSystemUsageSource
import org.arkikeskus.launcher.data.HostWidgetIdReleaser
import org.arkikeskus.launcher.data.SystemUsageSource
import org.arkikeskus.launcher.data.WidgetIdReleaser
import org.arkikeskus.launcher.data.IconChoicesSource
import org.arkikeskus.launcher.data.LauncherAppsSource
import org.arkikeskus.launcher.data.ProfileSystem
import org.arkikeskus.launcher.data.RoomAppLabelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.profile.ActiveHomeProfile
import org.arkikeskus.launcher.data.profile.DiskHomeProfileFiles
import org.arkikeskus.launcher.data.profile.HomeProfileFiles
import org.arkikeskus.launcher.data.profile.HomeProfileStore
import org.arkikeskus.launcher.data.profile.ProfiledPreferences
import javax.inject.Qualifier
import javax.inject.Singleton
import org.arkikeskus.launcher.model.IconChoices

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

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE home_items ADD COLUMN builtinType TEXT")
    }
}

/** v8: the app-label table (AppLabelEntity) beside the home layout; the statement is the one Room
 *  generates for the entity, validated by AppLabelMigrationTest. */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_labels` (`key` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`locale` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

/** v9: every home layout row gets its home profile (everything there is goes to the first), and "one item
 *  per cell" holds within a profile. The index statement is the one Room generates for the entity;
 *  validated by HomeProfileMigrationTest. */
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE home_items ADD COLUMN homeProfileId INTEGER NOT NULL DEFAULT 1")
        db.execSQL("DROP INDEX IF EXISTS `index_home_items_containerId_page_cellX_cellY`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_home_items_homeProfileId_containerId_page_cellX_cellY` " +
                "ON `home_items` (`homeProfileId`, `containerId`, `page`, `cellX`, `cellY`)",
        )
    }
}

/** Marks the settings file itself. Only the view over it ([ProfiledPreferences]) and the home profile list
 *  take it: every other reader and writer gets the view, as plain `DataStore<Preferences>`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainSettingsFile

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    @MainSettingsFile
    fun provideMainSettingsFile(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    fun provideHomeProfileFiles(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): HomeProfileFiles = DiskHomeProfileFiles(context, scope)

    @Provides
    @Singleton
    fun provideProfiledPreferences(
        @MainSettingsFile main: DataStore<Preferences>,
        files: HomeProfileFiles,
    ): ProfiledPreferences = ProfiledPreferences(main, files)

    /** The settings as the launcher reads them: what every home profile shares, with the own values of
     *  the profile that is on. With one profile this is the settings file itself. */
    @Provides
    @Singleton
    fun provideSettingsDataStore(view: ProfiledPreferences): DataStore<Preferences> = view

    @Provides
    @Singleton
    fun provideHomeProfileStore(
        @MainSettingsFile main: DataStore<Preferences>,
        view: ProfiledPreferences,
        files: HomeProfileFiles,
    ): HomeProfileStore = HomeProfileStore(main, view, files)

    @Provides
    fun provideActiveHomeProfile(store: HomeProfileStore): ActiveHomeProfile = store

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    fun provideIconChoices(source: IconChoicesSource): IconChoices = source

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        source: LauncherAppsSource,
        custom: CustomIconSource,
    ): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(AppIconKeyer())
            add(AppIconFetcher.Factory(source, custom))
            add(CustomIconKeyer())
            add(CustomIconFetcher.Factory(custom))
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
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()

    /** The layout rows of the home profile that is on. */
    @Provides
    @Singleton
    fun provideHomeItemDao(database: LauncherDatabase, active: ActiveHomeProfile): HomeItemDao =
        database.homeItemDao(active)

    @Provides
    @Singleton
    fun provideAppLabelStore(database: LauncherDatabase): AppLabelStore = RoomAppLabelStore(database.appLabelDao())

    @Provides
    fun provideAppRowResolver(source: LauncherAppsSource): AppRowResolver = source

    @Provides
    fun provideWidgetIdReleaser(impl: HostWidgetIdReleaser): WidgetIdReleaser = impl

    @Provides
    fun provideSystemUsageSource(impl: AndroidSystemUsageSource): SystemUsageSource = impl

    @Provides
    fun provideProfileSystem(impl: AndroidProfileSystem): ProfileSystem = impl
}
