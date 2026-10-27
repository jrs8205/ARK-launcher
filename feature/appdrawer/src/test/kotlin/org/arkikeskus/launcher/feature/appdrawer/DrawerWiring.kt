package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.search.AppSearchProvider
import org.arkikeskus.launcher.data.search.SearchAggregator
import java.io.File
import javax.inject.Provider
import kotlin.jvm.functions.Function0

/**
 * The drawer ViewModel's real collaborators without Hilt (the home module's ProductionWiring, cut
 * to what the drawer needs): an in-memory Room database, a DataStore on a temp file and the real
 * LauncherApps path. Room, DataStore and Coil are implementation details of core/data and not on
 * this module's test compile classpath, so the few calls that name their types go through reflection.
 */
internal class DrawerWiring(private val context: Application, testScope: TestScope) {
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LauncherDatabase = Class.forName("androidx.room.Room")
        .getMethod("inMemoryDatabaseBuilder", Context::class.java, Class::class.java)
        .invoke(null, context, LauncherDatabase::class.java)
        .let { builder -> builder.javaClass.getMethod("allowMainThreadQueries").invoke(builder) }
        .let { builder -> builder.javaClass.getMethod("build").invoke(builder) as LauncherDatabase }
    private val dao = LauncherDatabase::class.java.getMethod("homeItemDao").invoke(db) as HomeItemDao

    private val dataStore: Any = Class.forName("androidx.datastore.preferences.core.PreferenceDataStoreFactory").let { factory ->
        val file = File.createTempFile("launcher_settings", ".preferences_pb", context.cacheDir).also { it.delete() }
        val produceFile: () -> File = { file }
        factory.methods
            .single { it.name == "create" && it.parameterCount == 4 && it.parameterTypes.last() == Function0::class.java }
            .invoke(factory.getField("INSTANCE").get(null), null, emptyList<Any>(), dataStoreScope, produceFile)
    }
    val settingsRepository = SettingsRepository::class.java.constructors.single().newInstance(dataStore) as SettingsRepository
    private val appUsageRepository = AppUsageRepository::class.java.constructors.single().newInstance(dataStore) as AppUsageRepository

    private val iconPacks = IconPackRepository(context)
    private val source = LauncherAppsSource::class.java.constructors.single()
        .newInstance(context, iconPacks, Provider<Any?> { error("unused") }) as LauncherAppsSource

    private val appScope =
        CoroutineScope(testScope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScope.testScheduler))

    private val noProfiles = object : ProfileSystem {
        override fun snapshot(): List<ProfileSnapshot> =
            listOf(ProfileSnapshot(serial = 0, kind = ProfileKind.Own, badged = false, paused = false))
        override fun changes(): Flow<Unit> = emptyFlow()
        override fun requestQuietMode(serial: Long, enabled: Boolean): Boolean = error("no work profile")
    }
    private val workProfileRepository = WorkProfileRepository(noProfiles, appScope)
    val appRepository = AppRepository(source, settingsRepository, appUsageRepository, workProfileRepository, appScope)
    private val homeLayoutRepository = HomeLayoutRepository(db, dao, source)

    fun viewModel(): AppDrawerViewModel = AppDrawerViewModel(
        context,
        appRepository,
        settingsRepository,
        homeLayoutRepository,
        NotificationBadgeRepository(),
        SearchAggregator(setOf(AppSearchProvider(appRepository, settingsRepository))),
        appUsageRepository,
        workProfileRepository,
        CustomIconStore(settingsRepository, CustomIconSource(context, iconPacks)),
    )

    fun clear(viewModel: AppDrawerViewModel) = ViewModelStore().run {
        put("drawer", viewModel)
        clear()
    }

    fun close() {
        dataStoreScope.cancel()
        db.javaClass.getMethod("close").invoke(db)
    }
}
