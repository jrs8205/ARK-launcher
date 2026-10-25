package org.arkikeskus.launcher

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The "Open with" import replaces the layout on one tap over another app's window, so another app's
 * overlay must not be able to trick that tap: from Android 12 the launcher hides such overlays while
 * its confirmation is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OverlayGuardTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun privateFlags(params: WindowManager.LayoutParams): Int =
        WindowManager.LayoutParams::class.java.getField("privateFlags").getInt(params)

    @Test fun theLauncherMayHideOverlays() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()

        assertThat(requested).asList().contains(Manifest.permission.HIDE_OVERLAY_WINDOWS)
    }

    @Test fun overlaysAreHiddenWhileTheImportIsShown() {
        shadowOf(context).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val window = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().window

        window.hideOtherAppsOverlays()

        assertThat(privateFlags(window.attributes) and SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS).isNotEqualTo(0)
    }

    /** Android 11 has no such switch; the dialog's own touch filter covers it there. */
    @Config(sdk = [30])
    @Test fun android11IsLeftAlone() {
        val window = Robolectric.buildActivity(ComponentActivity::class.java).setup().get().window

        window.hideOtherAppsOverlays()

        assertThat(privateFlags(window.attributes) and SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS).isEqualTo(0)
    }

    private companion object {
        /** `WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (hidden). */
        const val SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS = 1 shl 19
    }
}
