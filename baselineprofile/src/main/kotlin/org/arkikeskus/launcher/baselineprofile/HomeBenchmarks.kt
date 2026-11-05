package org.arkikeskus.launcher.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The frames of the home gestures (the pages swiped, the drawer swiped up, scrolled and closed) with
 * and without the shipped baseline profile, on a real phone:
 *
 *     ANDROID_SERIAL=<phone> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
 *         -Pandroid.testInstrumentationRunnerArguments.class=org.arkikeskus.launcher.baselineprofile.HomeBenchmarks
 *
 * Frame durations and overruns (P50/P90/P99) go to logcat and the connected test's output folder.
 */
@RunWith(AndroidJUnit4::class)
class HomeBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val packageName: String = targetAppId()

    @Before
    fun pastTheIntro() = skipTheIntroIfShown(packageName)

    @Test
    fun pagesNone() = frames(CompilationMode.None()) { swipePages() }

    @Test
    fun pagesShippedProfile() = frames(CompilationMode.Partial(BaselineProfileMode.Require)) { swipePages() }

    @Test
    fun drawerNone() = frames(CompilationMode.None()) { drawerRoundTrip() }

    @Test
    fun drawerShippedProfile() = frames(CompilationMode.Partial(BaselineProfileMode.Require)) { drawerRoundTrip() }

    private fun MacrobenchmarkScope.drawerRoundTrip() {
        openDrawer()
        scrollDrawer()
        closeDrawer()
    }

    private fun frames(compilationMode: CompilationMode, journey: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startHome()
        },
        measureBlock = journey,
    )

    private companion object {
        const val ITERATIONS = 5
    }
}
