package org.arkikeskus.launcher.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the launcher's baseline profile and startup profile on the connected device:
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * with one emulator or phone attached whose launcher has its first-run intro behind it. The recorded
 * rules land in `app/src/main/generated/baselineProfiles/` and ship inside the release APK, so ART
 * compiles these paths right after an install instead of waiting for days of usage data.
 *
 * The journeys are the ones that stutter right after an update: the cold start, the home pages swiped
 * both ways and, above all, the app drawer swiped up, scrolled and closed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val packageName: String =
        InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("targetAppId was not passed as an instrumentation runner argument")

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

    private fun MacrobenchmarkScope.startHome() {
        startActivityAndWait()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.swipePages() {
        val y = (device.displayHeight * 0.45f).toInt()
        val left = (device.displayWidth * 0.15f).toInt()
        val right = (device.displayWidth * 0.85f).toInt()
        repeat(2) {
            device.swipe(right, y, left, y, SWIPE_STEPS)
            device.waitForIdle()
        }
        repeat(2) {
            device.swipe(left, y, right, y, SWIPE_STEPS)
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.openDrawer() {
        val x = device.displayWidth / 2
        device.swipe(x, (device.displayHeight * 0.70f).toInt(), x, (device.displayHeight * 0.15f).toInt(), SWIPE_STEPS)
        check(device.wait(Until.hasObject(drawerSearchField()), WAIT_MS)) { "the app drawer did not open on the swipe-up" }
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.scrollDrawer() {
        val x = device.displayWidth / 2
        val high = (device.displayHeight * 0.30f).toInt()
        val low = (device.displayHeight * 0.85f).toInt()
        repeat(2) {
            device.swipe(x, low, x, high, SWIPE_STEPS)
            device.waitForIdle()
        }
        device.swipe(x, high, x, low, SWIPE_STEPS)
        device.waitForIdle()
    }

    /** Back closes the drawer wherever its list has scrolled to; a swipe down would scroll first. */
    private fun MacrobenchmarkScope.closeDrawer() {
        device.pressBack()
        check(device.wait(Until.gone(drawerSearchField()), WAIT_MS)) { "the app drawer did not close on back" }
        device.waitForIdle()
    }

    /** The drawer's search field, found by its hint in the device's own language. */
    private fun drawerSearchField(): BySelector {
        val resources = InstrumentationRegistry.getInstrumentation().context.packageManager
            .getResourcesForApplication(packageName)
        val id = resources.getIdentifier("app_drawer_search_hint", "string", packageName)
        check(id != 0) { "app_drawer_search_hint is not a string of $packageName" }
        return By.text(resources.getString(id))
    }

    private companion object {
        /** UiAutomator steps of about 5 ms each: a quick flick, the way a finger opens the drawer. */
        const val SWIPE_STEPS = 12
        const val WAIT_MS = 3_000L

        /** The rule stops once three runs in a row record the same profile; this caps a drifting one. */
        const val MAX_ITERATIONS = 8
    }
}
