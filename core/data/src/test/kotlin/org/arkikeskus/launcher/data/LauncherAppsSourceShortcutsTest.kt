package org.arkikeskus.launcher.data

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Looper
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
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

/** The system's "an app's shortcuts changed" callback reaches the launcher as (package, profile serial). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherAppsSourceShortcutsTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val source = LauncherAppsSource(context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, AppLabelCache(InMemoryAppLabelStore()))

    @Test fun anAppChangingItsShortcutsIsReportedWithItsProfileSerial() = runTest {
        val changes = mutableListOf<Set<Pair<String, Long>>>()
        // The callback is registered while the app list is collected (for the process lifetime in the app).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.appsFlow().collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.shortcutChanges.collect { changes += it } }

        val chat = ShortcutInfo.Builder(context, "chat").setShortLabel("Chat").setIntent(Intent(Intent.ACTION_VIEW)).build()
        shadowOf(context.getSystemService(LauncherApps::class.java)).addDynamicShortcut(chat)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(changes).containsExactly(setOf(context.packageName to 0L))
    }

    /** [packageName] publishes a shortcut: the system reports the change under the shortcut's own package. */
    private fun publishShortcut(packageName: String, id: String = "s") {
        val owner = object : ContextWrapper(context) {
            override fun getPackageName() = packageName
        }
        val info = ShortcutInfo.Builder(owner, id).setShortLabel(id).setIntent(Intent(Intent.ACTION_VIEW)).build()
        shadowOf(context.getSystemService(LauncherApps::class.java)).addDynamicShortcut(info)
    }

    /** The home collector, held up by the first batch it gets (it waits for the shortcut pin lock
     *  during a restore) until [busy] completes; every batch it was handed ends up in [delivered]. */
    private fun TestScope.collectWhileBusy(busy: CompletableDeferred<Unit>, delivered: MutableList<Set<Pair<String, Long>>>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.appsFlow().collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.shortcutChanges.collect { owners ->
                busy.await()
                delivered += owners
            }
        }
    }

    @Test fun changesOfMoreAppsThanABufferWouldHoldAllReachABusyCollector() = runTest {
        val delivered = mutableListOf<Set<Pair<String, Long>>>()
        val busy = CompletableDeferred<Unit>()
        collectWhileBusy(busy, delivered)
        val owners = (1..20).map { "com.app$it" }

        owners.forEach { publishShortcut(it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(delivered).isEmpty()
        busy.complete(Unit)
        advanceUntilIdle()

        assertThat(delivered.flatten()).containsExactlyElementsIn(owners.map { it to 0L })
    }

    @Test fun anAppRepublishingWhileTheCollectorIsBusyIsDeliveredOnceItIsFreeWithoutABacklog() = runTest {
        val delivered = mutableListOf<Set<Pair<String, Long>>>()
        val busy = CompletableDeferred<Unit>()
        collectWhileBusy(busy, delivered)

        // A chat app re-publishes on every message: the first change holds the collector up, the rest pool.
        repeat(40) { publishShortcut("com.chat", id = "message$it") }
        shadowOf(Looper.getMainLooper()).idle()
        busy.complete(Unit)
        advanceUntilIdle()

        assertThat(delivered.flatten().toSet()).containsExactly("com.chat" to 0L)
        assertThat(delivered.size).isAtMost(2) // the one it was holding, and the pooled rest as one
    }

    @Test fun aCollectorThatArrivesLateGetsWhatPooledBeforeIt() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.appsFlow().collect {} }
        publishShortcut("com.chat")
        shadowOf(Looper.getMainLooper()).idle()

        val delivered = mutableListOf<Set<Pair<String, Long>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.shortcutChanges.collect { delivered += it } }
        advanceUntilIdle()

        assertThat(delivered).containsExactly(setOf("com.chat" to 0L))
    }
}
