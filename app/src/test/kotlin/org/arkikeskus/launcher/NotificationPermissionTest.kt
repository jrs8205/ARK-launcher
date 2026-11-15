package org.arkikeskus.launcher

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The permission to send notifications exists for one thing, the screensaver's battery-full notification, and
 * is asked for only by the user's own tap on something that names it: that notification's switch, or the
 * permission's own row on the Permissions page. Never at first run, never in a grant-all chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationPermissionTest {
    private val mainSources = listOf("../app/src/main", "../core", "../feature")

    /** The only files that may name the permission, each with what it does with it. Two of them ask for it. */
    private val allowed = mapOf(
        "BatteryFullNotifier.kt" to "checks that it is held before posting",
        "ScreensaverSection.kt" to "the battery-full switch asks for it",
        "PermissionsSection.kt" to "the permission's own row",
        "BackupRepository.kt" to "checks that it is held: a restored switch is off on a phone that does not hold it",
    )

    private fun kotlinMainFiles(): List<File> = mainSources.flatMap { dir ->
        val root = File(dir)
        assertWithMessage("$root is missing: the test runs from the app module's directory").that(root.isDirectory).isTrue()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.invariantSeparatorsPath.let { "/src/main/" in it && "/build/" !in it } }
            .toList()
    }

    @Test
    fun `the permission is named only where the user's own tap asks for it`() {
        val naming = kotlinMainFiles().filter { "POST_NOTIFICATIONS" in it.readText() }.map { it.name }

        assertThat(naming).containsExactlyElementsIn(allowed.keys)
    }

    @Test
    fun `the manifest asks for the two permissions the screensaver brought, and says so`() {
        val context = RuntimeEnvironment.getApplication()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()

        assertThat(requested).contains(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(requested).contains(Manifest.permission.WAKE_LOCK)
    }
}
