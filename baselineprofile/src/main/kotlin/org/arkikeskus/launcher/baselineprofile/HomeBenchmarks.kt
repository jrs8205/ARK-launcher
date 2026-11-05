package org.arkikeskus.launcher.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The frames of the app drawer swiped up, scrolled and closed, with and without the shipped baseline
 * profile, on a real phone (the owner's original complaint after every update was this swipe):
 *
 *     ANDROID_SERIAL=<phone> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
 *         -Pandroid.testInstrumentationRunnerArguments.class=org.arkikeskus.launcher.baselineprofile.HomeBenchmarks
 *
 * A warm start: the launcher under test is brought up in the setup and stays alive across the
 * iterations (a cold start would be killed again right before the measured block, and the swipes
 * would land on whatever launcher is the phone's home). The home pages are not timed: a fresh
 * install has one page, and a swipe across one page draws nothing.
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
    fun drawerNone() = drawer(CompilationMode.None())

    @Test
    fun drawerShippedProfile() = drawer(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun drawer(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startHome()
        },
    ) {
        openDrawer()
        scrollDrawer()
        closeDrawer()
    }

    private companion object {
        const val ITERATIONS = 5
    }
}
