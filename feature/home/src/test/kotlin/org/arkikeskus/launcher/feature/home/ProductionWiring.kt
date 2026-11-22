package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.WorkProfileRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.local.inHomeProfile
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.shadows.ShadowUserManager
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.profile.ActiveHomeProfile
import org.arkikeskus.launcher.data.profile.DiskHomeProfileFiles
import org.arkikeskus.launcher.data.profile.HomeProfileRepository
import org.arkikeskus.launcher.data.profile.HomeProfileStore
import org.arkikeskus.launcher.launcher.system.SignalMonitor
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
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
internal class ProductionWiring(
    private val context: Application,
    testScope: TestScope,
    /** The settings file a wiring before this one wrote and let go of ([closeAndLetGoOfTheFiles]): this one
     *  is then a later start of the same launcher. None: a launcher that starts for the first time. */
    settingsOn: File? = null,
) {
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Where the settings file is on the disk. */
    val settingsOnDisk: File =
        settingsOn ?: File.createTempFile("launcher_settings", ".preferences_pb", context.cacheDir).also { it.delete() }

    val db: LauncherDatabase = Class.forName("androidx.room.Room")
        .getMethod("inMemoryDatabaseBuilder", Context::class.java, Class::class.java)
        .invoke(null, context, LauncherDatabase::class.java)
        .let { builder -> builder.javaClass.getMethod("allowMainThreadQueries").invoke(builder) }
        .let { builder -> builder.javaClass.getMethod("build").invoke(builder) as LauncherDatabase }

    /** The settings file itself. Everything but the profile list reads and writes it through [dataStore]. */
    private val settingsFile: Any = Class.forName("androidx.datastore.preferences.core.PreferenceDataStoreFactory").let { factory ->
        val produceFile: () -> File = { settingsOnDisk }
        factory.methods
            .single { it.name == "create" && it.parameterCount == 4 && it.parameterTypes.last() == Function0::class.java }
            .invoke(factory.getField("INSTANCE").get(null), null, emptyList<Any>(), dataStoreScope, produceFile)
    }
    private val profileFiles = DiskHomeProfileFiles(context, dataStoreScope)

    /** The settings as the launcher sees them: what the home profiles share, with the own values of the one
     *  that is on. With one profile this is the settings file itself. */
    private val dataStore: Any = Class.forName("org.arkikeskus.launcher.data.profile.ProfiledPreferences")
        .constructors.single().newInstance(settingsFile, profileFiles)
    private val profileStore =
        HomeProfileStore::class.java.constructors.single().newInstance(settingsFile, dataStore, profileFiles) as HomeProfileStore

    /** The rows of the home profile that is on. */
    val dao = LauncherDatabase::class.java.getMethod("homeItemDao", ActiveHomeProfile::class.java).invoke(db, profileStore) as HomeItemDao
    val settingsRepository =
        SettingsRepository::class.java.constructors.first { it.parameterCount == 2 }.newInstance(dataStore, dataStore) as SettingsRepository
    private val appUsageRepository = AppUsageRepository::class.java.constructors.single().newInstance(dataStore) as AppUsageRepository

    /** The image loader is only used for icons; its type (Coil) is what keeps this reflective. */
    /** The app labels persist in the same in-memory database, as in the app. */
    private val labelCache = AppLabelCache(RoomAppLabelStore(LauncherDatabase::class.java.getMethod("appLabelDao").invoke(db) as AppLabelDao))
    /** No work profile on the test device, and no profile broadcasts. */
    private val noProfiles = object : ProfileSystem {
        override fun snapshot(): List<ProfileSnapshot> =
            listOf(ProfileSnapshot(serial = 0, kind = ProfileKind.Own, badged = false, paused = false))
        override fun changes(): Flow<Unit> = emptyFlow()
        override fun requestQuietMode(serial: Long, enabled: Boolean): Boolean = error("no work profile")
        override fun privateSpaceEntryHidden(serial: Long): Boolean? = null
        override fun privateInstallSender(serial: Long): android.content.IntentSender? = null
        override fun privateSettingsTarget(): org.arkikeskus.launcher.data.PrivateSettingsTarget? = null
    }
    private val source = LauncherAppsSource::class.java.constructors.single()
        .newInstance(context, IconPackRepository(context), Provider<Any?> { error("unused") }, labelCache, noProfiles) as LauncherAppsSource

    /** Eagerly shared, like the application scope in the app: the LauncherApps callback registers
     *  right here, so a shortcut published after this is reported whether or not anyone collects. */
    private val appScope =
        CoroutineScope(testScope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScope.testScheduler))

    val workProfileRepository = WorkProfileRepository(noProfiles, appScope)
    val appRepository = AppRepository(
        source, settingsRepository, appUsageRepository, workProfileRepository,
        PrivateSpaceRepository(noProfiles, workProfileRepository, source, settingsRepository, appScope), appScope,
    )
    val homeLayoutRepository = HomeLayoutRepository(db, dao, source)
    val homeProfiles = HomeProfileRepository(profileStore, homeLayoutRepository, settingsRepository, source, DrawerWallpaperStore(context)) { }

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

    /** The launcher is the home app (it may ask which shortcuts exist), the user is unlocked and the test
     *  app is enabled: only then can a pinned shortcut be told to be gone (the check keeps a row it cannot judge). */
    fun allowShortcutQueries() {
        val launcherApps = shadowOf(context.getSystemService(LauncherApps::class.java))
        launcherApps.setHasShortcutHostPermission(true)
        launcherApps.addEnabledPackage(Process.myUserHandle(), context.packageName)
        shadowOf(context.getSystemService(UserManager::class.java))
            .setUserState(Process.myUserHandle(), ShadowUserManager.UserState.STATE_RUNNING_UNLOCKED)
    }

    /** The test app takes the shortcut [shortcutId] away for good, and the system tells of a change in the
     *  app's shortcuts (here by a new one appearing), as it does for any change. */
    fun withdrawShortcut(shortcutId: String) {
        ReflectionHelpers.getStaticField<MutableList<ShortcutInfo>>(ShadowLauncherApps::class.java, "shortcuts")
            .removeAll { it.id == shortcutId }
        publishShortcut("after-$shortcutId")
    }

    /** The rows of the home profile [profileId], whichever profile is on. */
    suspend fun rowsOfProfile(profileId: Long): List<HomeItemEntity> = inHomeProfile(profileId) { dao.getAll() }

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

    fun clear(viewModel: HomeViewModel) {
        letThePinLockGo()
        ViewModelStore().run {
            put("home", viewModel)
            clear()
        }
    }

    /**
     * Waits until nobody is at the pin lock any more. The lock is one for the whole process, and so for every
     * test of a run. A ViewModel's coroutine that was just handed the lock runs when the main looper next
     * does; a test that ended right then left that turn in the looper's queue, which is emptied between
     * tests, and the lock stayed taken for every test after it (a removed shortcut's own change report sends
     * the ViewModel to the lock a moment after the removal let it go). The lock is fair, so whoever asked
     * before this has had its turn when this gets it.
     */
    private fun letThePinLockGo() {
        val free = CoroutineScope(Dispatchers.Default).launch { ShortcutPinLock.withLock { } }
        val deadline = System.currentTimeMillis() + 5_000
        while (!free.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        free.cancel()
    }

    fun close() {
        dataStoreScope.cancel()
        db.javaClass.getMethod("close").invoke(db)
    }

    /** [close], and not back before the stores have let go of their files: a file has one store at a time,
     *  and a wiring made after this one opens the same files ([settingsOnDisk]). */
    suspend fun closeAndLetGoOfTheFiles() {
        dataStoreScope.coroutineContext[Job]!!.cancelAndJoin()
        db.javaClass.getMethod("close").invoke(db)
    }
}

/**
 * Lists an app ([packageName], [label], launcher activity [className]) in the test device's
 * LauncherApps, installed and enabled, so a [ProductionWiring] built AFTER this call lists it from
 * its first query (the app list is queried eagerly, so add apps before the wiring). The two hidden
 * constructors are the API 33+ shape (`LauncherActivityInfoInternal(ActivityInfo,
 * IncrementalStatesInfo, UserHandle)`); the label is non-localized so no resource lookup runs.
 */
internal fun installFakeApp(context: Context, packageName: String, label: String, className: String = "$packageName.Main") {
    val user: UserHandle = Process.myUserHandle()
    val applicationInfo = ApplicationInfo().apply {
        this.packageName = packageName
        flags = ApplicationInfo.FLAG_INSTALLED
    }
    val activityInfo = ActivityInfo().apply {
        this.packageName = packageName
        name = className
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
