package org.arkikeskus.launcher

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * A work app says its profile to a screen reader ("Mail, Work") wherever the launcher lists apps: beside its
 * personal twin both would otherwise say the one name. The profile reaches the icons through a provider at
 * the root of a window, so every window that lists apps has to have it. The Settings window was without:
 * its lists (hidden apps, the app pickers, the drawer folders) said a work app's bare name.
 */
class WorkProfileMarksProvidedTest {
    private val activities = File("src/main/kotlin/org/arkikeskus/launcher")

    /** The windows that list apps of every profile. */
    private val listingApps = listOf("LauncherActivity.kt", "SettingsActivity.kt")

    @Test
    fun `every window that lists apps tells its icons the work profile`() {
        assertWithMessage("$activities is missing: the test runs from the app module's directory").that(activities.isDirectory).isTrue()
        for (name in listingApps) {
            val source = File(activities, name).readText()
            assertWithMessage("$name sets its content without WorkProfileMarks").that(source).contains("WorkProfileMarks(")
            // Around the content, not beside it: the provider has to be above the screen it is for.
            val provider = source.indexOf("WorkProfileMarks(")
            val screen = listOf("SettingsScreen(", "LauncherShell(").map { source.indexOf(it) }.filter { it >= 0 }.min()
            assertWithMessage("$name: the provider comes before the screen").that(provider).isLessThan(screen)
        }
    }

    @Test
    fun `no other window sets content that lists apps`() {
        val withContent = activities.listFiles().orEmpty()
            .filter { it.extension == "kt" && "setContent" in it.readText() }
            .map { it.name }

        // The pin request shows one shortcut or widget and the backup import no app at all.
        assertThat(withContent).containsExactly(
            "LauncherActivity.kt", "SettingsActivity.kt", "PinRequestActivity.kt", "BackupImportActivity.kt", "BackOnHome.kt",
        )
    }
}
