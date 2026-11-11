package org.arkikeskus.launcher.baselineprofile

import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * The journeys through the launcher that the baseline profile records and the benchmarks time, driven
 * through UiAutomator from the test's own process: the cold start, the home pages swiped both ways,
 * the app drawer swiped up, scrolled and closed, and the Settings screen opened from the home menu.
 */

/** The launcher under test: the bench build's own application id, passed by the Gradle plugin. */
internal fun targetAppId(): String =
    InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId was not passed as an instrumentation runner argument")

/** UiAutomator steps of about 5 ms each: a quick flick, the way a finger opens the drawer. */
internal const val SWIPE_STEPS = 12
internal const val WAIT_MS = 3_000L

/**
 * A cold start up to the home with its rows on screen. [startActivityAndWait] returns at the first
 * frame (the wallpaper and an empty grid); the rows load from the database a moment later, home
 * reports itself fully drawn and the activity draws once more. The settle keeps all of that inside
 * the measured trace: StartupTimingMetric needs a rendered frame after reportFullyDrawn, or it fails
 * ("No Choreographer#doFrame ends after reportFullyDrawn"). The settle itself is not part of any
 * metric: the start is timed from the trace's own timestamps.
 */
internal fun MacrobenchmarkScope.startHome() {
    startActivityAndWait()
    device.waitForIdle()
    SystemClock.sleep(SETTLE_MS)
}

/** How long a start is given to load its rows, report and draw once more, after the first frame. */
private const val SETTLE_MS = 1_500L

internal fun MacrobenchmarkScope.swipePages() {
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

internal fun MacrobenchmarkScope.openDrawer() {
    val x = device.displayWidth / 2
    device.swipe(x, (device.displayHeight * 0.70f).toInt(), x, (device.displayHeight * 0.15f).toInt(), SWIPE_STEPS)
    check(device.wait(Until.hasObject(drawerSearchField(packageName)), WAIT_MS)) { "the app drawer did not open on the swipe-up" }
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.scrollDrawer() {
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
internal fun MacrobenchmarkScope.closeDrawer() {
    device.pressBack()
    check(device.wait(Until.gone(drawerSearchField(packageName)), WAIT_MS)) { "the app drawer did not close on back" }
    device.waitForIdle()
}

/**
 * Settings from the home menu: a long-press on an empty spot low on the page (above the dock, under
 * the seeded rows), the "Launcher" entry, the main page of Settings scrolled once, the Home screen
 * category opened and its page scrolled once, then Back to the main page and Back out.
 */
internal fun MacrobenchmarkScope.openSettingsAndBack() {
    val x = device.displayWidth / 2
    val y = (device.displayHeight * 0.72f).toInt()
    device.swipe(x, y, x, y, LONG_PRESS_STEPS)
    val entry = By.text(appString(packageName, "home_options_settings"))
    check(device.wait(Until.hasObject(entry), WAIT_MS)) { "the home menu did not open on a long-press" }
    device.findObject(entry).click()
    // The screen's own title at the top: a section title lower down may be off screen (UiAutomator
    // sees only what is laid out) and Settings is a second activity, slower to come up than a popup.
    val title = By.text(appString(packageName, "settings_title"))
    check(device.wait(Until.hasObject(title), 2 * WAIT_MS)) { "Settings did not open from the home menu" }
    device.waitForIdle()
    val top = (device.displayHeight * 0.30f).toInt()
    val bottom = (device.displayHeight * 0.80f).toInt()
    device.swipe(x, bottom, x, top, SWIPE_STEPS)
    device.waitForIdle()
    device.swipe(x, top, x, bottom, SWIPE_STEPS)
    device.waitForIdle()
    // A category row is one node whose text starts with the category's name (its summary follows);
    // its page has no "Settings" title, and its first row is the grid's column count.
    val category = By.textStartsWith(appString(packageName, "settings_home"))
    check(device.wait(Until.hasObject(category), WAIT_MS)) { "the Home screen category is not on the main page" }
    device.findObject(category).click()
    val firstRow = By.text(appString(packageName, "settings_columns"))
    check(device.wait(Until.hasObject(firstRow), WAIT_MS)) { "the Home screen page did not open" }
    device.waitForIdle()
    device.swipe(x, bottom, x, top, SWIPE_STEPS)
    device.waitForIdle()
    device.pressBack()
    check(device.wait(Until.hasObject(title), WAIT_MS)) { "back did not return to the main page of Settings" }
    device.waitForIdle()
    device.pressBack()
    check(device.wait(Until.gone(title), WAIT_MS)) { "Settings did not close on back" }
    device.waitForIdle()
}

/**
 * The first-run intro of a fresh install, dismissed once with its Skip button so every later start
 * lands on the home screen. The phones' bench build starts fresh (the connected run installs it and
 * uninstalls it afterwards); the emulator's is seeded past the intro by the recording recipe.
 */
internal fun skipTheIntroIfShown(packageName: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val context = instrumentation.context
    val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: error("$packageName has no launch intent")
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    device.wait(Until.hasObject(By.pkg(packageName).depth(0)), WAIT_MS)
    val skip = By.text(appString(packageName, "onboarding_skip"))
    if (device.wait(Until.hasObject(skip), 1_500L)) {
        device.findObject(skip).click()
        device.wait(Until.gone(skip), WAIT_MS)
    }
    device.waitForIdle()
    device.pressHome()
    device.waitForIdle()
}

/** The drawer's search field, found by its hint in the device's own language. */
internal fun drawerSearchField(packageName: String): BySelector = By.text(appString(packageName, "app_drawer_search_hint"))

/** A string of the launcher under test by its resource name, in the device's own language. */
internal fun appString(packageName: String, name: String): String {
    val resources = InstrumentationRegistry.getInstrumentation().context.packageManager.getResourcesForApplication(packageName)
    val id = resources.getIdentifier(name, "string", packageName)
    check(id != 0) { "$name is not a string of $packageName" }
    return resources.getString(id)
}

/** A still press of about 600 ms: past any long-press delay the launcher offers. */
private const val LONG_PRESS_STEPS = 120
