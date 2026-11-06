package org.arkikeskus.launcher.data

import android.app.Application
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * `LauncherApps.Callback` never reports a profile coming, going or pausing, so the app list takes
 * an outside reload signal (the profile broadcasts) and re-queries the system on each — every
 * query is emitted, so one signal shows as one more emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherAppsSourceReloadTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val source = LauncherAppsSource(context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, AppLabelCache(InMemoryAppLabelStore()))
    private val signals = MutableSharedFlow<Unit>()
    private val emissions = MutableStateFlow(0)

    private fun TestScope.collectApps() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.appsFlow(reloadSignals = signals).collect { emissions.update { it + 1 } }
        }
    }

    /** The query runs on a real IO thread, so wait in real time, not the scheduler's. */
    private suspend fun awaitEmissions(count: Int) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { emissions.first { it >= count } }
    }

    @Test fun aReloadSignalRequeriesTheAppList() = runTest {
        collectApps()
        awaitEmissions(1)

        signals.emit(Unit)

        awaitEmissions(2)
        assertThat(emissions.value).isEqualTo(2)
    }

    @Test fun aBurstOfReloadSignalsEndsWithACurrentListAndAtMostOneReloadPerSignal() = runTest {
        collectApps()
        awaitEmissions(1)

        repeat(3) { signals.emit(Unit) }

        awaitEmissions(2)
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(300) } // let any further reloads land
        assertThat(emissions.value).isAtMost(4)
    }
}
