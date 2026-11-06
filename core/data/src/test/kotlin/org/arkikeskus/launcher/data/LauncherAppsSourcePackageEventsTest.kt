package org.arkikeskus.launcher.data

import android.app.Application
import android.content.pm.LauncherApps
import android.os.Looper
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * The system's package callbacks reach the launcher's one collector in batches, like
 * [LauncherAppsSource.shortcutChanges]: nothing is lost while the collector is busy, and a
 * collector that arrives late — a ViewModel re-created behind Settings — gets what came before it.
 * A shared flow with a fixed buffer dropped the events past its capacity, so the home rows of an
 * app uninstalled in such a burst stayed as ghosts until the next launch's sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherAppsSourcePackageEventsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val source = LauncherAppsSource(context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, AppLabelCache(InMemoryAppLabelStore()))
    private val launcherApps = shadowOf(context.getSystemService(LauncherApps::class.java))

    /** The callback is registered while the app list is collected (for the process lifetime in the app). */
    private fun TestScope.collectApps() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.appsFlow().collect {} }
    }

    @Test fun removalsOfMoreAppsThanABufferWouldHoldAllReachABusyCollector() = runTest {
        collectApps()
        val delivered = mutableListOf<Set<Pair<String, Long>>>()
        val busy = CompletableDeferred<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.packageRemovals.collect { removed ->
                busy.await()
                delivered += removed
            }
        }
        val packages = (1..20).map { "com.app$it" }

        packages.forEach { launcherApps.notifyPackageRemoved(it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(delivered).isEmpty()
        busy.complete(Unit)
        advanceUntilIdle()

        assertThat(delivered.flatten()).containsExactlyElementsIn(packages.map { it to 0L })
    }

    @Test fun eventsOfMoreAppsThanABufferWouldHoldAllReachABusyCollector() = runTest {
        collectApps()
        val delivered = mutableListOf<Set<String>>()
        val busy = CompletableDeferred<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.packageEvents.collect { changed ->
                busy.await()
                delivered += changed
            }
        }
        val packages = (1..20).map { "com.app$it" }

        packages.forEach { launcherApps.notifyPackageAdded(it) }
        shadowOf(Looper.getMainLooper()).idle()
        busy.complete(Unit)
        advanceUntilIdle()

        assertThat(delivered.flatten()).containsExactlyElementsIn(packages)
    }

    @Test fun aRemovalCollectorThatArrivesLateGetsWhatCameBeforeIt() = runTest {
        collectApps()
        launcherApps.notifyPackageRemoved("com.gone")
        shadowOf(Looper.getMainLooper()).idle()

        val delivered = mutableListOf<Set<Pair<String, Long>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.packageRemovals.collect { delivered += it } }
        advanceUntilIdle()

        assertThat(delivered).containsExactly(setOf("com.gone" to 0L))
    }

    @Test fun aRemovedPackageIsAlsoReportedAsAPackageEvent() = runTest {
        collectApps()
        val events = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.packageEvents.collect { events += it } }

        launcherApps.notifyPackageRemoved("com.gone")
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        assertThat(events.flatten()).containsExactly("com.gone")
    }
}
