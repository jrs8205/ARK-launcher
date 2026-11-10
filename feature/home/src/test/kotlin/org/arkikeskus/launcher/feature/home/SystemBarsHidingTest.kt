package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.view.WindowInsets
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two "hide the system bar" settings are independent: each bar is hidden when its own setting is
 * on and shown again when it is off, whatever the other one says (a request to hide the bottom bar
 * for a home screen with nothing but the wallpaper and the widgets on it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SystemBarsHidingTest {
    private val status = WindowInsets.Type.statusBars()
    private val navigation = WindowInsets.Type.navigationBars()

    @Test fun nothingIsHiddenByDefault() {
        val request = systemBarsRequest(hideStatusBar = false, hideNavigationBar = false)
        assertThat(request.hide).isEqualTo(0)
        assertThat(request.show).isEqualTo(status or navigation)
    }

    @Test fun theStatusBarAloneIsHidden() {
        val request = systemBarsRequest(hideStatusBar = true, hideNavigationBar = false)
        assertThat(request.hide).isEqualTo(status)
        assertThat(request.show).isEqualTo(navigation)
    }

    @Test fun theNavigationBarAloneIsHidden() {
        val request = systemBarsRequest(hideStatusBar = false, hideNavigationBar = true)
        assertThat(request.hide).isEqualTo(navigation)
        assertThat(request.show).isEqualTo(status)
    }

    @Test fun bothBarsAreHiddenTogether() {
        val request = systemBarsRequest(hideStatusBar = true, hideNavigationBar = true)
        assertThat(request.hide).isEqualTo(status or navigation)
        assertThat(request.show).isEqualTo(0)
    }
}
