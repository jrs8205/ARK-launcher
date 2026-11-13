package org.arkikeskus.launcher.data

import android.app.Application
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.util.ReflectionHelpers
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
    private val labels = AppLabelCache(InMemoryAppLabelStore())
    private val profiles = FakeProfileSystem(emptyList())
    private val source = LauncherAppsSource(context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, labels, profiles)
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

    // The private space's package events. A private app can be the twin of a personal one (the same package),
    // and everything the callbacks feed is keyed by package: the pools the home rows are cleaned from, the label
    // cache that is written to the disk, the icon epochs.

    private val second: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)
    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)

    /** A second profile of [kind] with the twin of the personal com.bank in it; the first scan has listed both. */
    private suspend fun TestScope.aSecondProfileWithATwin(kind: ProfileKind) {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 10, "Second", 0)
        installFakeApp(context, "com.bank", "Bank")
        installFakeApp(context, "com.bank", "Bank", second)
        profiles.profiles = listOf(own, ProfileSnapshot(10, kind, badged = false, paused = false))
        collectApps()
        // The scan runs on a real IO thread: wait for it in real time.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (labels.label("com.bank/com.bank.Main/0") == null) delay(10) }
        }
    }

    /** Every package callback and the shortcut callback of [packageName] in [user], as the system delivers them. */
    private fun everyEvent(packageName: String, user: UserHandle) {
        val registered: List<android.util.Pair<LauncherApps.Callback, Handler>> =
            ReflectionHelpers.getStaticField(ShadowLauncherApps::class.java, "callbacks")
        for (entry in registered) {
            entry.second.post {
                entry.first.onPackageAdded(packageName, user)
                entry.first.onPackageChanged(packageName, user)
                entry.first.onPackagesAvailable(arrayOf(packageName), user, true)
                entry.first.onPackagesUnavailable(arrayOf(packageName), user, false)
                entry.first.onShortcutsChanged(packageName, mutableListOf(), user)
                entry.first.onPackageRemoved(packageName, user)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Heard {
        val events = mutableListOf<Set<String>>()
        val removals = mutableListOf<Set<Pair<String, Long>>>()
        val shortcuts = mutableListOf<Set<Pair<String, Long>>>()
    }

    private fun TestScope.listen(): Heard = Heard().also { heard ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.packageEvents.collect { heard.events += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.packageRemovals.collect { heard.removals += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.shortcutChanges.collect { heard.shortcuts += it } }
    }

    @Test fun aPrivateAppsEventsReachNoCollectorAndLeaveItsPersonalTwinAlone() = runTest {
        aSecondProfileWithATwin(ProfileKind.Private)
        val heard = listen()
        val epochs = source.iconEpochs.value

        everyEvent("com.bank", second)
        advanceUntilIdle()

        // The home rows are cleaned from these: a removal heard here would take the personal twin's icon away.
        assertThat(heard.events).isEmpty()
        assertThat(heard.removals).isEmpty()
        assertThat(heard.shortcuts).isEmpty()
        // The personal twin's label stays in the cache, and the package is not marked for a fresh read.
        assertThat(labels.label("com.bank/com.bank.Main/0")).isEqualTo("com.bank")
        assertThat(labels.generations()).doesNotContainKey("com.bank")
        assertThat(source.iconEpochs.value).isEqualTo(epochs)
    }

    @Test fun aPrivateAppsEventReadsThePrivateListAgain() = runTest {
        aSecondProfileWithATwin(ProfileKind.Private)
        withContext(Dispatchers.Default) { withTimeout(10_000) { source.privateApps.first { it.size == 1 } } }

        // A second private app arrives; nothing but its package event tells the launcher.
        installFakeApp(context, "com.secret.notes", "Notes", second)
        everyEvent("com.secret.notes", second)

        val private = withContext(Dispatchers.Default) { withTimeout(10_000) { source.privateApps.first { it.size == 2 } } }
        assertThat(private.map { it.packageName }).containsExactly("com.bank", "com.secret.notes")
        assertThat(labels.label("com.secret.notes/com.secret.notes.Main/10")).isNull()
        assertThat(labels.generations()).doesNotContainKey("com.secret.notes")
    }

    @Test fun aWorkProfilesEventsAreHeardAsBefore() = runTest {
        aSecondProfileWithATwin(ProfileKind.Managed)
        val heard = listen()

        everyEvent("com.bank", second)
        advanceUntilIdle()

        assertThat(heard.events.flatten()).contains("com.bank")
        assertThat(heard.removals.flatten()).containsExactly("com.bank" to 10L)
        assertThat(heard.shortcuts.flatten()).containsExactly("com.bank" to 10L)
        assertThat(labels.generations()).containsKey("com.bank")
    }

    @Test fun theEventsOfAProfileOfUnknownKindAreNotHeardAtAll() = runTest {
        aSecondProfileWithATwin(ProfileKind.Unknown)
        val heard = listen()

        everyEvent("com.bank", second)
        advanceUntilIdle()

        assertThat(heard.events).isEmpty()
        assertThat(heard.removals).isEmpty()
        assertThat(heard.shortcuts).isEmpty()
        assertThat(labels.label("com.bank/com.bank.Main/0")).isEqualTo("com.bank")
        assertThat(labels.generations()).doesNotContainKey("com.bank")
    }
}
