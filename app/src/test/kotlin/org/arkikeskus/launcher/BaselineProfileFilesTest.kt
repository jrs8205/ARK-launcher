package org.arkikeskus.launcher

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The committed baseline profile (recorded by :baselineprofile, shipped in the release APK) must cover
 * the paths that stutter right after an update: the home screen with its pages, the app drawer and
 * the shell that slides it up. A recording whose drawer swipe silently missed would pass the
 * generator and ship a profile without the drawer; this is the guard against that.
 */
class BaselineProfileFilesTest {
    private val profiles = File("src/main/generated/baselineProfiles")

    private fun rules(name: String): List<String> {
        val file = File(profiles, name)
        assertWithMessage("$file is missing; run ./gradlew :app:generateBaselineProfile").that(file.isFile).isTrue()
        return file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun assertCovers(rules: List<String>, path: String) {
        assertWithMessage("no rule for $path").that(rules.any { path in it }).isTrue()
    }

    @Test fun theBaselineProfileCoversTheHomeScreenAndTheAppDrawer() {
        val rules = rules("baseline-prof.txt")
        assertCovers(rules, "org/arkikeskus/launcher/LauncherActivity")
        assertCovers(rules, "org/arkikeskus/launcher/ui/LauncherShell")
        assertCovers(rules, "org/arkikeskus/launcher/feature/home/Workspace")
        assertCovers(rules, "org/arkikeskus/launcher/feature/appdrawer/AppDrawerScreen")
    }

    @Test fun theStartupProfileListsTheColdStart() {
        val rules = rules("startup-prof.txt")
        assertCovers(rules, "org/arkikeskus/launcher/LauncherActivity")
        assertCovers(rules, "org/arkikeskus/launcher/feature/home/")
    }
}
