package org.arkikeskus.launcher.data

import android.app.Application
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUserManager

/**
 * Android 15 can archive an app: its data stays, the launcher still lists it, and a tap restores it.
 * To the package manager such an app is "not installed", which the home sweep read as uninstalled
 * and so dropped the app's home icon and folder place the next time the launcher started. An
 * archived app is only hidden for now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchivedAppTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val source = LauncherAppsSource(context, IconPackRepository(context), Provider<ImageLoader> { error("unused") })
    private val launcherApps = shadowOf(context.getSystemService(LauncherApps::class.java))

    @Before fun unlockTheUser() = shadowOf(context.getSystemService(UserManager::class.java))
        .setUserState(Process.myUserHandle(), ShadowUserManager.UserState.STATE_RUNNING_UNLOCKED)

    @Test fun anArchivedAppIsOnlyHiddenForNow() {
        // Archived: no application info for the user, yet LauncherApps still reports it enabled.
        launcherApps.addEnabledPackage(Process.myUserHandle(), "com.archived")

        assertThat(source.isAppInstalled("com.archived", 0L)).isFalse()
        assertThat(source.isTemporarilyUnavailable("com.archived", 0L)).isTrue()
    }

    @Test fun anUninstalledAppIsGoneForGood() {
        assertThat(source.isAppInstalled("com.gone", 0L)).isFalse()
        assertThat(source.isTemporarilyUnavailable("com.gone", 0L)).isFalse()
    }
}
