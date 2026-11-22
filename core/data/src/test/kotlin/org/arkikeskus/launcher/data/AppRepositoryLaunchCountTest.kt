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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    /** [dispatcher] is what the repository's own coroutines run on: the unconfined one runs them at once, the
     *  standard one only when the test lets time go on, as the application's scope does on a busy phone. */
    private fun TestScope.repository(dispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler)): AppRepository {
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
     * Review round 7. A start waits for Android's report only in the count it is asked in. Here the count is
     * switched on while Android is starting the app, so the start's own note is written into the new count: it
     * is scored, and it is not kept waiting for a report the new count's record (which begins after the start)
     * will never have. The count is told by the moment the start was asked for, so the switch is turned a
     * moment later on the clock too (in the same millisecond the start would be of the new count).
     */
    @Test fun aStartAskedBeforeTheCountWasSwitchedOnIsScoredAndWaitsForNoReport() = runTest {
        val apps = repository()
        advanceUntilIdle()
        StartingLauncherApps.whileStarting = {
            Thread.sleep(2)
            runBlocking { settings.setCountSystemUsage(true, now = System.currentTimeMillis()) }
        }

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

    /**
     * Review round 10. Whether a start is of the count that is on is told from the store, where the start's
     * own note is written. It was told from a copy of the store's state that a coroutine of the repository kept
     * up: right after the switch was turned on the copy could still say "off", the start was stamped so, its
     * note did not keep it waiting for Android's report, and the next read of Android's record counted the
     * report as an opening from elsewhere. Two launches for one. Here nothing of the repository's own runs
     * between the switch and the start.
     */
    @Test fun aStartAskedRightAfterTheCountWasSwitchedOnWaitsForItsReport() = runTest {
        val apps = repository(StandardTestDispatcher(testScheduler))
        // Everything the repository follows has been read once: the count is off. (The repository's work is
        // background work of the test, which only runCurrent runs when nothing else is waiting.)
        runCurrent()
        settings.setCountSystemUsage(true, now = System.currentTimeMillis())

        assertThat(apps.launch(mail).isSuccess).isTrue()
        runCurrent()

        assertThat(usage.usage.first().keys).containsExactly(mail.key)
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
