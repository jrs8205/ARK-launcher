package org.arkikeskus.launcher.data

import android.app.Application
import android.os.Process
import android.os.UserHandle
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.AppItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * A start in the launcher while apps opened elsewhere are counted too (issue #33). Android reports the same
 * start a moment later, and the two are told to be one by what the launcher remembers of its own: the app, and
 * the moment the start was ASKED for, which Android's report can only come after. Only a start Android does
 * report is remembered: it tells the launcher nothing of a work profile's apps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [StartingLauncherApps::class])
class AppRepositoryLaunchCountTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val store = InMemoryDataStore()
    private val settings = SettingsRepository(store)
    private val usage = AppUsageRepository(store)
    private val profiles = FakeProfileSystem(
        listOf(
            ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false),
            ProfileSnapshot(10, ProfileKind.Managed, badged = true, paused = false),
        ),
    )
    private val workUser: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)
    private val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0, "Mail")
    private val workMail = AppItem("com.mail", "com.mail.Main", workUser, 10, "Mail")

    @Before fun nothingStartedYet() = StartingLauncherApps.started.clear()

    @After fun startsTakeNoTime() {
        StartingLauncherApps.whileStarting = null
    }

    private fun TestScope.repository(): AppRepository {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val source = LauncherAppsSource(
            context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, AppLabelCache(InMemoryAppLabelStore()), profiles,
        )
        val work = WorkProfileRepository(profiles, scope, dispatcher, 5_000)
        val privateSpace = PrivateSpaceRepository(profiles, work, source, settings, scope)
        return AppRepository(source, settings, usage, work, privateSpace, scope)
    }

    @Test fun aWorkAppsStartIsScoredButOnlyThePersonalOneIsRememberedForAndroidsReport() = runTest {
        settings.setCountSystemUsage(true, now = 1L)
        val apps = repository()

        assertThat(apps.launch(workMail).isSuccess).isTrue()
        assertThat(apps.launch(mail).isSuccess).isTrue()
        advanceUntilIdle()

        assertThat(usage.usage.first().keys).containsExactly(workMail.key, mail.key)
        assertThat(usage.ownLaunches.first().map { it.first }).containsExactly(mail.key)
    }

    /**
     * Review round 7. A start is stamped with the count it is asked in, and waits for Android's report only in
     * that one. Here the count is switched on while Android is starting the app, so the start's own note is
     * written into the new count: it is scored, and it is not kept waiting for a report the new count's record
     * (which begins after the start) will never have.
     */
    @Test fun aStartAskedBeforeTheCountWasSwitchedOnIsScoredAndWaitsForNoReport() = runTest {
        val apps = repository()
        advanceUntilIdle()
        StartingLauncherApps.whileStarting = { runBlocking { settings.setCountSystemUsage(true, now = System.currentTimeMillis()) } }

        assertThat(apps.launch(mail).isSuccess).isTrue()
        advanceUntilIdle()

        assertThat(usage.usage.first().keys).containsExactly(mail.key)
        assertThat(usage.ownLaunches.first()).isEmpty()

        // The next start is asked in the count that is on, and waits for its report.
        StartingLauncherApps.whileStarting = null
        assertThat(apps.launch(mail).isSuccess).isTrue()
        advanceUntilIdle()
        assertThat(usage.ownLaunches.first().map { it.first }).containsExactly(mail.key)
    }

    /** The start itself takes time, and the app's screen can be up before the launcher gets to note anything.
     *  What is noted is the moment the start was asked for: Android's report is never before that. */
    @Test fun theMomentRememberedIsWhenTheStartWasAskedFor() = runTest {
        settings.setCountSystemUsage(true, now = 1L)
        val apps = repository()
        var reachedAndroid = 0L
        StartingLauncherApps.whileStarting = {
            reachedAndroid = System.currentTimeMillis()
            Thread.sleep(60)
        }
        val before = System.currentTimeMillis()

        assertThat(apps.launch(mail).isSuccess).isTrue()
        advanceUntilIdle()

        val (key, remembered) = usage.ownLaunches.first().single()
        assertThat(key).isEqualTo(mail.key)
        assertThat(remembered).isAtLeast(before)
        assertThat(remembered).isAtMost(reachedAndroid)
        assertThat(usage.usage.first().getValue(mail.key).lastUsed).isEqualTo(remembered)
    }
}
