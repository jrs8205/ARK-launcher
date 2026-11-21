package org.arkikeskus.launcher.data

import android.app.Application
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.AppItem
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import javax.inject.Provider

/** A LauncherApps that starts what it is asked to (the stock shadow has no start at all). */
@Implements(LauncherApps::class)
class StartingLauncherApps : ShadowLauncherApps() {
    @Implementation
    protected fun startMainActivity(component: ComponentName, user: UserHandle, sourceBounds: Rect?, opts: Bundle?) {
        started += component to user
        whileStarting?.invoke()
    }

    companion object {
        val started = mutableListOf<Pair<ComponentName, UserHandle>>()

        /** Runs as an app is started: the time the start itself takes. */
        var whileStarting: (() -> Unit)? = null
    }
}

/**
 * A start is counted for the "most used" ranking, and the count is written to the disk under the app's key. A
 * private app's start must leave no such trace, whichever way it was started: the app repository is the second
 * guard behind the private space's own start, which never counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [StartingLauncherApps::class])
class AppRepositoryPrivateLaunchTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val store = InMemoryDataStore()
    private val settings = SettingsRepository(store)
    private val usage = AppUsageRepository(store)
    private val profiles = FakeProfileSystem(
        listOf(
            ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false),
            ProfileSnapshot(11, ProfileKind.Private, badged = false, paused = false),
        ),
    )
    private val privateUser: UserHandle = UserHandle.getUserHandleForUid(11 * 100_000)

    @Before fun nothingStartedYet() = StartingLauncherApps.started.clear()

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

    @Test fun aPersonalAppsStartIsCountedAsBefore() = runTest {
        val mail = AppItem("com.mail", "com.mail.Main", Process.myUserHandle(), 0, "Mail")

        assertThat(repository().launch(mail).isSuccess).isTrue()
        advanceUntilIdle()

        assertThat(StartingLauncherApps.started).containsExactly(mail.componentName to mail.user)
        assertThat(usage.usage.first().keys).containsExactly(mail.key)
    }

    @Test fun aPrivateAppsStartLeavesNoTraceInTheUsage() = runTest {
        val bank = AppItem("com.bank", "com.bank.Main", privateUser, 11, "Bank")

        assertThat(repository().launch(bank).isSuccess).isTrue()
        advanceUntilIdle()

        assertThat(StartingLauncherApps.started).containsExactly(bank.componentName to privateUser)
        assertThat(usage.usage.first()).isEmpty()
    }
}
