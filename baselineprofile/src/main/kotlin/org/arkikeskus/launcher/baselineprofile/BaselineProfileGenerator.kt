package org.arkikeskus.launcher.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the launcher's baseline profile and startup profile on the connected device:
 *
 *     ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
 *
 * with the one emulator or phone named by ANDROID_SERIAL attached, its launcher (the bench build,
 * org.arkikeskus.launcher.bench) seeded past its first-run intro. The recorded rules land in
 * `app/src/main/generated/baselineProfiles/` and ship inside the release APK, so ART compiles these
 * paths right after an install instead of waiting for days of usage data.
 *
 * The journeys ([LauncherJourneys.kt]) are the ones that stutter right after an update: the cold
 * start, the home pages swiped both ways, above all the app drawer swiped up, scrolled and closed,
 * and the Settings screen.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val packageName: String = targetAppId()

    /** Everything a cold start runs before the home screen is on screen: the startup profile. */
    @Test
    fun startup() = rule.collect(packageName = packageName, maxIterations = MAX_ITERATIONS, includeInStartupProfile = true) {
        startHome()
    }

    /** The home gestures: page swipes, then the drawer swiped up, scrolled and closed. */
    @Test
    fun homeAndDrawer() = rule.collect(packageName = packageName, maxIterations = MAX_ITERATIONS) {
        startHome()
        swipePages()
        repeat(3) {
            openDrawer()
            scrollDrawer()
            closeDrawer()
        }
    }

    /** Settings from the home menu, its first screen scrolled, and back. */
    @Test
    fun settings() = rule.collect(packageName = packageName, maxIterations = MAX_ITERATIONS) {
        startHome()
        openSettingsAndBack()
    }

    private companion object {
        /** The rule stops once three runs in a row record the same profile; this caps a drifting one. */
        const val MAX_ITERATIONS = 8
    }
}
