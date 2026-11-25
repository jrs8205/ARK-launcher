package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.AppUsageRepository
import org.arkikeskus.launcher.data.CustomIconSource
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.AppLabelCache
import org.arkikeskus.launcher.data.LauncherAppsSource
import org.arkikeskus.launcher.data.RoomAppLabelStore
import org.arkikeskus.launcher.data.local.AppLabelDao
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.ProfileKind
import org.arkikeskus.launcher.data.ProfileSnapshot
import org.arkikeskus.launcher.data.PrivateSpaceRepository
import org.arkikeskus.launcher.data.ProfileSystem
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.WorkProfileRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.profile.ActiveHomeProfile
import org.arkikeskus.launcher.data.profile.DiskHomeProfileFiles
import org.arkikeskus.launcher.data.profile.HomeProfileFiles
import org.arkikeskus.launcher.data.testing.InMemoryHomeProfileFiles
import org.arkikeskus.launcher.data.testing.InMemoryPreferences
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.data.profile.HomeProfileStore
import org.arkikeskus.launcher.data.search.AppSearchProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import org.arkikeskus.launcher.data.search.PrivateSpaceSearchProvider
import org.arkikeskus.launcher.data.search.SearchProvider
import org.arkikeskus.launcher.model.SearchResult
import kotlinx.coroutines.CompletableDeferred
import org.arkikeskus.launcher.data.search.SearchAggregator
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlin.jvm.functions.Function0

/**
 * The drawer ViewModel's real collaborators without Hilt (the home module's ProductionWiring, cut
 * to what the drawer needs): an in-memory Room database, a DataStore on a temp file (or, where a test says so, in memory) and the real
 * LauncherApps path. Room, DataStore and Coil are implementation details of core/data and not on
 * this module's test compile classpath, so the few calls that name their types go through reflection.
 */
internal class DrawerWiring(
    private val context: Application,
    testScope: TestScope,
    /** The profiles of the test device as the platform types them; only the own user unless a test says so. */
    initialProfiles: List<ProfileSnapshot> = listOf(ProfileSnapshot(serial = 0, kind = ProfileKind.Own, badged = false, paused = false)),
    /** False keeps the settings (and the further home profiles' own) in memory ([InMemoryPreferences]): for a
     *  test that writes them and is not about the file. */
    settingsOnDisk: Boolean = true,
    /** True keeps the settings file's first read from ending until the test lets it ([settingsFirstReadMayEnd]):
     *  for a test that is about a slow one (a busy machine makes it slow by itself). Whoever asks the file
     *  first makes that read, and everyone else who asks waits for it. */
    settingsFirstReadWaits: Boolean = false,
) {
    /** Open once the settings file's first read is under way, where it waits to be let go on. */
    val settingsFirstReadBegan = CountDownLatch(1)

    /** Opened by the test: the settings file's first read may end. */
    val settingsFirstReadMayEnd = CountDownLatch(1)

    /** The device's profiles now. A lock or unlock request is granted at once and reported, as Android does. */
    var profiles: List<ProfileSnapshot> = initialProfiles
        private set
    private val profileChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** The quiet-mode requests the launcher made: the profile's serial and what was asked. */
    val quietModeRequests = mutableListOf<Pair<Long, Boolean>>()
    /** A request is taken but nothing changes, as when the user cancels Android's own prompt. */
    var refuseQuietMode = false
    /** While set and not completed, every search waits on it: a slow source (contacts) that holds the answer back. */
    @Volatile var searchGate: CompletableDeferred<Unit>? = null
    private val slowSource = object : SearchProvider {
        override suspend fun isEnabled(): Boolean = true
        override suspend fun query(query: String): List<SearchResult> {
            searchGate?.await()
            return emptyList()
        }
    }

    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LauncherDatabase = Class.forName("androidx.room.Room")
        .getMethod("inMemoryDatabaseBuilder", Context::class.java, Class::class.java)
        .invoke(null, context, LauncherDatabase::class.java)
        .let { builder -> builder.javaClass.getMethod("allowMainThreadQueries").invoke(builder) }
        .let { builder -> builder.javaClass.getMethod("build").invoke(builder) as LauncherDatabase }
    /** The settings file itself. Everything but the profile list reads and writes it through [dataStore]. */
    private val settingsFile: Any = if (!settingsOnDisk) InMemoryPreferences() else Class.forName("androidx.datastore.preferences.core.PreferenceDataStoreFactory").let { factory ->
        val file = File.createTempFile("launcher_settings", ".preferences_pb", context.cacheDir).also { it.delete() }
        val produceFile: () -> File = { file }
        val atFirstRead = if (settingsFirstReadWaits) listOf(HeldFirstRead(settingsFirstReadBegan, settingsFirstReadMayEnd)) else emptyList()
        factory.methods
            .single { it.name == "create" && it.parameterCount == 4 && it.parameterTypes.last() == Function0::class.java }
            .invoke(factory.getField("INSTANCE").get(null), null, atFirstRead, dataStoreScope, produceFile)
    }

    /** The settings kept in memory, where the test asked for that: the way to make their writes fail. */
    val settingsInMemory: InMemoryPreferences? = settingsFile as? InMemoryPreferences
    private val profileFiles: HomeProfileFiles = if (settingsOnDisk) DiskHomeProfileFiles(context, dataStoreScope) else InMemoryHomeProfileFiles()

    /** The settings as the launcher sees them: what the home profiles share, with the own values of the one
     *  that is on. With one profile this is the settings file itself. */
    private val dataStore: Any = Class.forName("org.arkikeskus.launcher.data.profile.ProfiledPreferences")
        .constructors.single().newInstance(settingsFile, profileFiles)
    private val profileStore =
        HomeProfileStore::class.java.constructors.single().newInstance(settingsFile, dataStore, profileFiles) as HomeProfileStore

    /** The rows of the home profile that is on. */
    private val dao = LauncherDatabase::class.java.getMethod("homeItemDao", ActiveHomeProfile::class.java).invoke(db, profileStore) as HomeItemDao
    val settingsRepository =
        SettingsRepository::class.java.constructors.first { it.parameterCount == 2 }.newInstance(dataStore, dataStore) as SettingsRepository
    private val appUsageRepository = AppUsageRepository::class.java.constructors.single().newInstance(dataStore) as AppUsageRepository

    private val iconPacks = IconPackRepository(context)
    /** The app labels persist in the same in-memory database, as in the app. */
    private val labelCache = AppLabelCache(RoomAppLabelStore(LauncherDatabase::class.java.getMethod("appLabelDao").invoke(db) as AppLabelDao))
    private val noProfiles = object : ProfileSystem {
        override fun snapshot(): List<ProfileSnapshot> = profiles
        override fun changes(): Flow<Unit> = profileChanges
        override fun requestQuietMode(serial: Long, enabled: Boolean): Boolean {
            check(profiles.any { it.serial == serial }) { "no profile $serial" }
            quietModeRequests += serial to enabled
            if (refuseQuietMode) return false
            profiles = profiles.map { if (it.serial == serial) it.copy(paused = enabled) else it }
            profileChanges.tryEmit(Unit)
            return true
        }
        override fun privateSpaceEntryHidden(serial: Long): Boolean? = null
        override fun privateInstallSender(serial: Long): android.content.IntentSender? = null
        override fun privateSettingsTarget(): org.arkikeskus.launcher.data.PrivateSettingsTarget? = null
    }
    private val source = LauncherAppsSource::class.java.constructors.single()
        .newInstance(context, iconPacks, Provider<Any?> { error("unused") }, labelCache, noProfiles) as LauncherAppsSource

    private val appScope =
        CoroutineScope(testScope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScope.testScheduler))

    private val workProfileRepository = WorkProfileRepository(noProfiles, appScope)
    val privateSpaceRepository = PrivateSpaceRepository(noProfiles, workProfileRepository, source, settingsRepository, appScope)
    val appRepository = AppRepository(source, settingsRepository, appUsageRepository, workProfileRepository, privateSpaceRepository, appScope)
    val homeLayoutRepository = HomeLayoutRepository(db, dao, source)

    /** Making and switching home profiles, as Settings does. */
    val homeProfiles = HomeProfileRepository(profileStore, homeLayoutRepository, settingsRepository, source, DrawerWallpaperStore(context)) { }

    fun viewModel(): AppDrawerViewModel = AppDrawerViewModel(
        context,
        appRepository,
        settingsRepository,
        homeLayoutRepository,
        NotificationBadgeRepository(),
        SearchAggregator(
            setOf(AppSearchProvider(appRepository, settingsRepository), PrivateSpaceSearchProvider(context, privateSpaceRepository), slowSource),
        ),
        appUsageRepository,
        workProfileRepository,
        privateSpaceRepository,
        CustomIconStore(settingsRepository, CustomIconSource(context, iconPacks)),
        drawerWallpaper,
        // No usage access in these tests: nothing of Android's record is read.
        org.arkikeskus.launcher.data.SystemUsageSync(
            context,
            object : org.arkikeskus.launcher.data.SystemUsageSource {
                override fun hasAccess() = false
                override fun resumedBetween(fromMs: Long, toMs: Long) = emptyList<org.arkikeskus.launcher.data.AppOpening>()
            },
            appUsageRepository, appRepository,
        ),
    )

    /** The user's own picture behind the drawer (DrawerWallpaperStore), in the app's files. */
    val drawerWallpaper = DrawerWallpaperStore(context)

    fun clear(viewModel: AppDrawerViewModel) = ViewModelStore().run {
        put("drawer", viewModel)
        clear()
    }

    fun close() {
        dataStoreScope.cancel()
        db.javaClass.getMethod("close").invoke(db)
    }
}

/**
 * A first read of the settings file that does not end before the test lets it ([mayEnd]), or ten seconds
 * have passed. The file's store runs this once, inside the read that every other reader waits for, and
 * nothing is changed by it.
 */
private class HeldFirstRead(private val began: CountDownLatch, private val mayEnd: CountDownLatch) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        began.countDown()
        mayEnd.await(10, TimeUnit.SECONDS)
        return false
    }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData

    override suspend fun cleanUp() = Unit
}

/**
 * Runs [work], which asks something of the stores (the settings, the home rows, the home profiles), on
 * another thread while the main looper turns, and hands back what it gave or throws what it threw.
 *
 * In place of `runBlocking` on the test's own thread. That thread is the main thread, and a view model's
 * coroutines go on there only when its looper turns. The stores hand a lock on to whoever has waited for it
 * longest: the settings file makes its first read under one, and every change of the settings and every
 * switch of the home profile is made under one. Handed to a view model's coroutine, the lock is given back
 * when the looper turns, and a test that waits for it with the looper stopped waits for good. A run of the
 * whole test task stood still so on a busy machine, where the first read took long enough for a test to come
 * and wait behind the view model ([WhileTheMainLooperTurnsTest]).
 *
 * Not done within [limitMs]: the test fails ("Timed out waiting for [what]"), and never hangs. The work is
 * not this call's child, since a parent waits for its child, and work that cannot be cancelled would keep
 * the test waiting. It is told to end, and the looper turns on for as long again while it does: whoever has
 * the lock may need a turn of the looper to let go of it. Work that still has not ended by then is left
 * behind, and the failure is given all the same.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> whileTheMainLooperTurns(what: String = "what was asked of the stores", limitMs: Long = 10_000, work: suspend () -> T): T {
    val asked = CoroutineScope(Dispatchers.Default).async { work() }
    turnTheMainLooper(limitMs) { asked.isCompleted }
    if (!asked.isCompleted) {
        asked.cancel()
        turnTheMainLooper(limitMs) { asked.isCompleted }
        error("Timed out waiting for $what")
    }
    return asked.getCompleted()
}

/** Turns the main looper until [done], for [limitMs] at most. */
private fun turnTheMainLooper(limitMs: Long, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + limitMs
    while (!done() && System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (!done()) Thread.sleep(2)
    }
}

/**
 * Lists an app ([packageName], [label]) in the test device's LauncherApps, installed and enabled, so
 * a [DrawerWiring] built AFTER this call lists it from its first query (the drawer's app list is
 * queried eagerly, so add apps before the wiring). The two hidden constructors are the API 33+
 * shape (`LauncherActivityInfoInternal(ActivityInfo, IncrementalStatesInfo, UserHandle)`); the
 * label is non-localized so no resource lookup runs.
 */
internal fun installFakeApp(context: Context, packageName: String, label: String, user: UserHandle = Process.myUserHandle()) {
    val applicationInfo = ApplicationInfo().apply {
        this.packageName = packageName
        flags = ApplicationInfo.FLAG_INSTALLED
    }
    val activityInfo = ActivityInfo().apply {
        this.packageName = packageName
        name = "$packageName.Main"
        nonLocalizedLabel = label
        this.applicationInfo = applicationInfo
    }
    val internalClass = Class.forName("android.content.pm.LauncherActivityInfoInternal")
    val internal: Any = ReflectionHelpers.callConstructor(
        internalClass,
        from(ActivityInfo::class.java, activityInfo),
        from(Class.forName("android.content.pm.IncrementalStatesInfo"), null),
        from(UserHandle::class.java, user),
    )
    val info: LauncherActivityInfo = ReflectionHelpers.callConstructor(
        LauncherActivityInfo::class.java,
        from(Context::class.java, context),
        from(internalClass, internal),
    )
    val launcherApps = shadowOf(context.getSystemService(LauncherApps::class.java))
    launcherApps.addApplicationInfo(user, packageName, applicationInfo)
    launcherApps.addEnabledPackage(user, packageName)
    launcherApps.addActivity(user, info)
}
