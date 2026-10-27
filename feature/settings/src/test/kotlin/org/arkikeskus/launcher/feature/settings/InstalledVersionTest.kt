package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.content.pm.ApplicationInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The bottom of Settings names the installed build — app name, version and version code as the
 * package manager reports them — so it can be compared with the newest release at a glance. A
 * debuggable build says so: it installs beside the release and looks the same otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InstalledVersionTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun install(versionName: String, versionCode: Long, debuggable: Boolean) {
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        info.versionName = versionName
        info.longVersionCode = versionCode
        val app = info.applicationInfo!!
        // Only the debug bit: the shadow treats a cleared FLAG_INSTALLED as "not installed".
        app.flags = if (debuggable) app.flags or ApplicationInfo.FLAG_DEBUGGABLE else app.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        app.nonLocalizedLabel = "ARK-launcher"
    }

    @Test fun theInstalledBuildIsNamedWithItsVersionAndCode() {
        install("0.9.4", 61, debuggable = false)

        assertThat(installedVersionLabel(context)).isEqualTo("ARK-launcher 0.9.4 (61)")
    }

    @Test fun aDebuggableBuildSaysSo() {
        install("0.9.4", 61, debuggable = true)

        assertThat(installedVersionLabel(context)).isEqualTo("ARK-launcher 0.9.4-debug (61)")
    }
}
