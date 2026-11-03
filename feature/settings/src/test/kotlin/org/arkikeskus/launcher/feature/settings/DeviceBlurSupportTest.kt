package org.arkikeskus.launcher.feature.settings

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemProperties

/**
 * The device's blur support comes from the same system property the window manager reads for its
 * cross-window blur support, through the property API (not public, hence read by name): 0 on the
 * Fairphone 6, 1 on Pixels, unknown when unset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceBlurSupportTest {

    @Test fun aStackThatCannotBlurSaysSo() {
        ShadowSystemProperties.override(BLUR_SUPPORT_PROPERTY, "0")
        assertThat(systemProperty(BLUR_SUPPORT_PROPERTY)).isEqualTo("0")
        assertThat(deviceSupportsBlur()).isFalse()
    }

    @Test fun aStackThatBlursSaysSo() {
        ShadowSystemProperties.override(BLUR_SUPPORT_PROPERTY, "1")
        assertThat(deviceSupportsBlur()).isTrue()
    }

    @Test fun anUnsetPropertyLeavesTheSupportUnknown() {
        assertThat(deviceSupportsBlur()).isNull()
    }
}
