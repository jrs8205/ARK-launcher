package org.arkikeskus.launcher.data

import android.app.Application
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.profile.CountingStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import javax.inject.Provider

/**
 * Review round 12. The home screen follows apps' renamed launcher activities in the settings at every start.
 * It is a correction, and one the next start makes again: nothing of it is worth the home process. A settings
 * store that cannot be read just then leaves the keys as they are, and nothing is thrown. (A write that is
 * refused goes the same way out, through the same `catch`; no app here has a renamed activity to write for.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppRepositoryRenamedKeysTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val store = CountingStore()
    private val settings = SettingsRepository(store)
    private val profiles = FakeProfileSystem(listOf(ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)))

    @Test fun aSettingsStoreThatCannotBeReadLeavesTheKeysAsTheyAreAndThrowsNothing() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val source = LauncherAppsSource(
            context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, AppLabelCache(InMemoryAppLabelStore()), profiles,
        )
        val work = WorkProfileRepository(profiles, scope, dispatcher, 5_000)
        val privateSpace = PrivateSpaceRepository(profiles, work, source, settings, scope)
        val apps = AppRepository(source, settings, AppUsageRepository(store), work, privateSpace, scope)
        settings.setAppHidden("gone/gone.Main/0", true)
        runCurrent()

        store.failReads = IOException("the settings could not be read")

        apps.followRenamedActivitiesInSettings()

        // Nothing was thrown, and what is stored is as it was.
        store.failReads = null
        assertThat(settings.referencedAppKeys()).containsExactly("gone/gone.Main/0")
    }
}
