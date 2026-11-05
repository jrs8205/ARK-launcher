package org.arkikeskus.launcher.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How much the shipped baseline profile buys at a cold start, measured on a real phone (an emulator
 * runs on the PC's processor and says nothing about a phone):
 *
 *     ANDROID_SERIAL=<phone> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
 *         -Pandroid.testInstrumentationRunnerArguments.class=org.arkikeskus.launcher.baselineprofile.StartupBenchmarks
 *
 * Three compilations of the same bench build: none (what a fresh sideload runs until the profile is
 * compiled), the shipped profile (what the launcher runs after the first background compile), and
 * everything ahead of time (the ceiling). The time to initial display is the first frame; the time
 * to full display is the home's own report once its rows are up (HomeDrawnReporter). Results go to
 * logcat and `build/outputs/connected_android_test_additional_output/`.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val packageName: String = targetAppId()

    @Before
    fun pastTheIntro() = skipTheIntroIfShown(packageName)

    @Test
    fun startupNone() = startup(CompilationMode.None())

    @Test
    fun startupShippedProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun startupFull() = startup(CompilationMode.Full())

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = packageName,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startHome()
    }

    private companion object {
        const val ITERATIONS = 10
    }
}
