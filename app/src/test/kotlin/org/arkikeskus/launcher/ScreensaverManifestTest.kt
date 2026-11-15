package org.arkikeskus.launcher

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.dreams.DreamService
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.feature.screensaver.ScreensaverService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** How the manifest offers the launcher to Android as a screensaver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScreensaverManifestTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val packages get() = context.packageManager

    private val service
        get() = packages.getServiceInfo(ComponentName(context, ScreensaverService::class.java), PackageManager.GET_META_DATA)

    @Test
    fun `the description of the screensaver is under the name Android reads`() {
        // Under any other name Android's screensaver choice shows neither the preview picture nor the gear.
        assertThat(service.metaData.keySet()).contains(DreamService.DREAM_META_DATA)
    }

    @Test
    fun `the screensaver is offered to Android, and only Android may bind it`() {
        assertThat(service.exported).isTrue()
        assertThat(service.permission).isEqualTo(Manifest.permission.BIND_DREAM_SERVICE)
    }

    @Test
    fun `the class name Android stores for the chosen screensaver is the one it always was`() {
        assertThat(ScreensaverService::class.java.name)
            .isEqualTo("org.arkikeskus.launcher.feature.screensaver.ScreensaverService")
    }

    @Test
    fun `the alias the gear opens leads to Settings and is closed to other apps`() {
        val alias = packages.getActivityInfo(
            ComponentName(context.packageName, "org.arkikeskus.launcher.ScreensaverSettingsAlias"),
            0,
        )

        assertThat(alias.exported).isFalse()
        assertThat(alias.targetActivity).isEqualTo(SettingsActivity::class.java.name)
    }
}
