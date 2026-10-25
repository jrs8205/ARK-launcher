package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.AppUsageRepository
import org.arkikeskus.launcher.data.CustomIconSource
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.LauncherAppsSource
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.ProfileKind
import org.arkikeskus.launcher.data.ProfileSnapshot
import org.arkikeskus.launcher.data.ProfileSystem
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.WorkProfileRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.launcher.system.SignalMonitor
import org.robolectric.Shadows.shadowOf
import java.io.File
import javax.inject.Provider
import kotlin.jvm.functions.Function0

/**
 * The ViewModel's real collaborators, wired as DataModule wires them but without Hilt: an
 * in-memory Room database, a DataStore on a temp file and the real LauncherApps callback path.
 * Room, DataStore and Coil are implementation details of core/data and not on this module's
 * test compile classpath (they are on its runtime classpath), so the few calls that would name
 * their types go through reflection.
 */
internal class ProductionWiring(private val context: Application, testScope: TestScope) {
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: LauncherDatabase = Class.forName("androidx.room.Room")
        .getMethod("inMemoryDatabaseBuilder", Context::class.java, Class::class.java)
        .invoke(null, context, LauncherDatabase::class.java)
        .let { builder -> builder.javaClass.getMethod("allowMainThreadQueries").invoke(builder) }
        .let { builder -> builder.javaClass.getMethod("build").invoke(builder) as LauncherDatabase }
    val dao = LauncherDatabase::class.java.getMethod("homeItemDao").invoke(db) as HomeItemDao

    private val dataStore: Any = Class.forName("androidx.datastore.preferences.core.PreferenceDataStoreFactory").let { factory ->
        val file = File.createTempFile("launcher_settings", ".preferences_pb", context.cacheDir).also { it.delete() }
        val produceFile: () -> File = { file }
        factory.methods
            .single { it.name == "create" && it.parameterCount == 4 && it.parameterTypes.last() == Function0::class.java }
            .invoke(factory.getField("INSTANCE").get(null), null, emptyList<Any>(), dataStoreScope, produceFile)
    }
    val settingsRepository = SettingsRepository::class.java.constructors.single().newInstance(dataStore) as SettingsRepository
    private val appUsageRepository = AppUsageRepository::class.java.constructors.single().newInstance(dataStore) as AppUsageRepository

    /** The image loader is only used for icons; its type (Coil) is what keeps this reflective. */
    private val source = LauncherAppsSource::class.java.constructors.single()
        .newInstance(context, IconPackRepository(context), Provider<Any?> { error("unused") }) as LauncherAppsSource

    /** Eagerly shared, like the application scope in the app: the LauncherApps callback registers
     *  right here, so a shortcut published after this is reported whether or not anyone collects. */
    private val appScope =
        CoroutineScope(testScope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScope.testScheduler))

    /** No work profile on the test device, and no profile broadcasts. */
    private val noProfiles = object : ProfileSystem {
        override fun snapshot(): List<ProfileSnapshot> =
            listOf(ProfileSnapshot(serial = 0, kind = ProfileKind.Own, badged = false, paused = false))
        override fun changes(): Flow<Unit> = emptyFlow()
        override fun requestQuietMode(serial: Long, enabled: Boolean): Boolean = error("no work profile")
    }
    val workProfileRepository = WorkProfileRepository(noProfiles, appScope)
    val appRepository = AppRepository(source, settingsRepository, appUsageRepository, workProfileRepository, appScope)
    val homeLayoutRepository = HomeLayoutRepository(db, dao, source)

    suspend fun pinOnHome(shortcutId: String): Long = dao.insert(
        HomeItemEntity(packageName = context.packageName, shortcutId = shortcutId, page = 0, cellX = 0, cellY = 0),
    )

    /** Fills every page the pager shows with one page-sized widget each, so no home cell is free
     *  (a page left out by [exceptPage] stays empty). */
    suspend fun fillEveryShownPage(exceptPage: Int? = null) {
        val s = settingsRepository.settings.first()
        for (page in 0 until HomeLayoutRepository.MAX_PAGES) {
            if (page == exceptPage) continue
            dao.insert(
                HomeItemEntity(
                    page = page, cellX = 0, cellY = 0, spanX = s.homeColumns, spanY = s.homeRows,
                    appWidgetId = 1000 + page, widgetProvider = "com.widgets/.Full",
                ),
            )
        }
    }

    /** The test app publishes a shortcut; the system's callback runs once the main looper idles. */
    fun publishShortcut(shortcutId: String) {
        val info = ShortcutInfo.Builder(context, shortcutId).setShortLabel(shortcutId).setIntent(Intent(Intent.ACTION_VIEW)).build()
        shadowOf(context.getSystemService(LauncherApps::class.java)).addDynamicShortcut(info)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Creates the ViewModel on the main thread with the first-run work already done, as on
     *  every launch after the first. */
    suspend fun homeViewModel(): HomeViewModel {
        settingsRepository.setDefaultLayoutSeeded()
        settingsRepository.setOnboardingDone()
        return HomeViewModel(
            context, settingsRepository, appRepository, homeLayoutRepository, NotificationBadgeRepository(), SignalMonitor(context),
            CustomIconStore(settingsRepository, CustomIconSource(context, IconPackRepository(context))),
        )
    }

    fun clear(viewModel: HomeViewModel) = ViewModelStore().run {
        put("home", viewModel)
        clear()
    }

    fun close() {
        dataStoreScope.cancel()
        db.javaClass.getMethod("close").invoke(db)
    }
}
