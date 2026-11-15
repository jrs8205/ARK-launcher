package org.arkikeskus.launcher.data

import android.app.Application
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.room.Room
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.arkikeskus.launcher.data.backup.BackupCodec
import org.arkikeskus.launcher.data.backup.BackupRepository
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.arkikeskus.launcher.data.search.AppSearchProvider
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.SearchResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * The private space's promise in one run of the real parts: its apps are listed, searched past, started and
 * locked away again, and afterwards nothing that is kept (the database, the settings with the usage ranking,
 * a backup) names one of them. The personal app that goes through the same pipes does leave its traces, so
 * the absence is not an empty pipe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [StartingLauncherApps::class])
class PrivateSpaceNoTraceTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val privateUser: UserHandle = UserHandle.getUserHandleForUid(11 * 100_000)
    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)
    private fun space(locked: Boolean) = ProfileSnapshot(11, ProfileKind.Private, badged = false, paused = locked)

    private lateinit var db: LauncherDatabase
    private val store = InMemoryDataStore()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LauncherDatabase::class.java).allowMainThreadQueries().build()
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 11, "Private", 0)
        installFakeApp(context, "com.mail", "Mail")
        installFakeApp(context, "com.secret.bank", "Secret Bank", privateUser)
        StartingLauncherApps.started.clear()
    }

    @After fun tearDown() = db.close()

    /** The scans run on a real IO thread: wait for [condition] in real time. */
    private suspend fun await(what: String, condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    /** Every row of every table of the database, as text. */
    private fun databaseText(): String = buildString {
        val tables = ArrayList<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> while (c.moveToNext()) tables += c.getString(0) }
        for (table in tables) {
            db.query("SELECT * FROM `$table`", null).use { c ->
                while (c.moveToNext()) {
                    append(table).append(": ")
                    for (i in 0 until c.columnCount) append(c.getColumnName(i)).append('=').append(runCatching { c.getString(i) }.getOrNull()).append(' ')
                    append('\n')
                }
            }
        }
    }

    private fun assertNoTrace(where: String, text: String) {
        for (trace in listOf("com.secret.bank", "Secret Bank", "secret")) {
            assertWithMessage("$where names the private app ($trace)").that(text.contains(trace, ignoreCase = true)).isFalse()
        }
    }

    @Test fun nothingAboutAPrivateAppReachesTheDisk() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val profiles = FakeProfileSystem(listOf(own, space(locked = false)))
        val labels = AppLabelCache(RoomAppLabelStore(db.appLabelDao()))
        val settings = SettingsRepository(store)
        val usage = AppUsageRepository(store)
        val source = LauncherAppsSource(context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, labels, profiles)
        val work = WorkProfileRepository(profiles, scope, dispatcher, 5_000)
        val privateSpace = PrivateSpaceRepository(profiles, work, source, settings, scope)
        val apps = AppRepository(source, settings, usage, work, privateSpace, scope)
        val backup = BackupRepository(context, db.homeItemDao(), settings, source, org.arkikeskus.launcher.data.screensaver.ScreensaverSettingsStore(context))

        // Listed: the personal app in the shared list, the private one in its own.
        await("the first scan") { privateSpace.apps.value.isNotEmpty() }
        val shared = apps.apps.first()
        assertThat(shared.map { it.packageName }).containsExactly("com.mail")
        val bank: AppItem = privateSpace.apps.value.single()
        assertThat(bank.packageName).isEqualTo("com.secret.bank")

        // Searched past: the app search reads the shared list.
        val search = AppSearchProvider(apps, settings)
        assertThat(search.query("secret")).isEmpty()
        assertThat(search.query("bank")).isEmpty()
        assertThat(search.query("mail").map { (it as SearchResult.App).app.packageName }).containsExactly("com.mail")

        // Started, both ways a start can go; the personal app too, for comparison.
        assertThat(privateSpace.launch(bank).isSuccess).isTrue()
        assertThat(apps.launch(bank).isSuccess).isTrue()
        assertThat(apps.launch(shared.single()).isSuccess).isTrue()
        advanceUntilIdle()
        assertThat(StartingLauncherApps.started.map { it.first.packageName }).containsExactly("com.secret.bank", "com.secret.bank", "com.mail")

        // Locked away again.
        profiles.profiles = listOf(own, space(locked = true))
        profiles.broadcasts.emit(Unit)
        // The repository reads the profile again on an IO thread; with the state locked the list is empty.
        await("the lock") { privateSpace.state.value?.locked == true }
        assertThat(privateSpace.apps.value).isEmpty()
        await("the scan after the lock") { source.privateApps.value.isEmpty() }
        // Whatever the label cache holds goes to the database now.
        labels.persist()
        await("the personal label in the database") { databaseText().contains("com.mail/com.mail.Main/0") }

        // The pipes are live: the personal app left its traces.
        val preferences = store.data.first().asMap().entries.joinToString("\n") { "${it.key.name}=${it.value}" }
        assertThat(usage.usage.first().keys).containsExactly("com.mail/com.mail.Main/0")
        assertThat(preferences).contains("com.mail/com.mail.Main/0")
        // The private app left none.
        assertNoTrace("the database", databaseText())
        assertNoTrace("the settings", preferences)
        assertNoTrace("a backup", BackupCodec.encode(backup.exportDocument(createdAt = 0L, appVersion = "test")))
    }
}
