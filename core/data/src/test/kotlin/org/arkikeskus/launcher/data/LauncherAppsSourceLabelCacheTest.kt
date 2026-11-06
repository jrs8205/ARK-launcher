package org.arkikeskus.launcher.data

import android.app.Application
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.arkikeskus.launcher.model.AppItem
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * Reading an app's label opens its package's resources — every installed package's, on a cold
 * start, before the home rows could show. The first scan of a process hands out the labels the
 * last scan persisted and emits at once; it then re-reads those labels in the background and emits
 * again only if one changed while the launcher was not running. A package the system never listed
 * before is read in the first scan, and the persisted labels end up current.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherAppsSourceLabelCacheTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val store = InMemoryAppLabelStore()
    private val locale = context.resources.configuration.locales.toLanguageTags()
    private val emissions = MutableStateFlow<List<List<AppItem>>>(emptyList())
    private lateinit var source: LauncherAppsSource

    // Robolectric resolves a fake app's label to its package name: that is "the app's own label" here.
    @Before fun installApps() {
        installFakeApp(context, "com.mail", "Mail")
        installFakeApp(context, "com.chat", "Chat")
    }

    private fun TestScope.collectApps() {
        source = LauncherAppsSource(context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, AppLabelCache(store))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.appsFlow().collect { apps -> emissions.update { it + listOf(apps) } }
        }
    }

    /** The scan runs on a real IO thread, so wait in real time, not the scheduler's. */
    private suspend fun awaitEmissions(count: Int) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { emissions.first { it.size >= count } }
    }

    /** The scan persists AFTER its last emission, on the same IO thread: wait for the write itself. */
    private suspend fun awaitWrites(count: Int) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (store.writes < count) kotlinx.coroutines.delay(10) }
    }

    private fun labels(apps: List<AppItem>) = apps.associate { it.packageName to it.label }

    @Test fun thePersistedLabelsShowFirstAndAChangedOneIsCorrectedInASecondEmission() = runTest {
        store.stored[locale] = mapOf("com.mail/com.mail.Main/0" to "Old Mail", "com.chat/com.chat.Main/0" to "com.chat")

        collectApps()
        val seen = awaitEmissions(2)
        awaitWrites(1)

        assertThat(labels(seen[0])).containsExactly("com.mail", "Old Mail", "com.chat", "com.chat")
        assertThat(seen[0].map { it.label }).isInOrder(compareBy<String> { it.lowercase() })
        assertThat(labels(seen[1])).containsExactly("com.mail", "com.mail", "com.chat", "com.chat")
        assertThat(seen[1].map { it.label }).isInOrder(compareBy<String> { it.lowercase() })
        assertThat(store.stored[locale]).containsExactly("com.mail/com.mail.Main/0", "com.mail", "com.chat/com.chat.Main/0", "com.chat")
    }

    @Test fun currentPersistedLabelsAreEmittedOnceAndAnUnknownAppIsReadAtOnce() = runTest {
        store.stored[locale] = mapOf("com.mail/com.mail.Main/0" to "com.mail", "com.gone/com.gone.Main/0" to "Gone")

        collectApps()
        val seen = awaitEmissions(1)
        awaitWrites(1) // the scan's end: a second emission would have come before it

        assertThat(emissions.value).hasSize(1)
        assertThat(labels(seen[0])).containsExactly("com.mail", "com.mail", "com.chat", "com.chat")
        // The uninstalled app's entry is gone, the newly read one is kept, for the next start.
        assertThat(store.stored[locale]).containsExactly("com.mail/com.mail.Main/0", "com.mail", "com.chat/com.chat.Main/0", "com.chat")
    }

    @Test fun withNothingPersistedEveryLabelIsReadAndThenPersisted() = runTest {
        collectApps()
        val seen = awaitEmissions(1)
        awaitWrites(1)

        assertThat(emissions.value).hasSize(1)
        assertThat(labels(seen[0])).containsExactly("com.mail", "com.mail", "com.chat", "com.chat")
        assertThat(store.stored[locale]).containsExactly("com.mail/com.mail.Main/0", "com.mail", "com.chat/com.chat.Main/0", "com.chat")
    }
}
