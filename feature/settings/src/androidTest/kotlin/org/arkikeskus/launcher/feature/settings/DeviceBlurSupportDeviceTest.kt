package org.arkikeskus.launcher.feature.settings

import android.os.Build
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real Android the blur-support property must be readable from an app process (the property API
 * is reached by name, and SELinux must let an app read the display stack's namespace), and a system
 * that blurs across windows right now must report a device that supports it.
 */
@RunWith(AndroidJUnit4::class)
class DeviceBlurSupportDeviceTest {

    @Test fun theBlurSupportPropertyIsReadableFromAnAppProcess() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val raw = systemProperty(BLUR_SUPPORT_PROPERTY)
        assertWithMessage("the property API answered").that(raw).isNotNull()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enabledNow = context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        val supported = deviceSupportsBlur()
        assertWithMessage("$BLUR_SUPPORT_PROPERTY='$raw', cross-window blur enabled now=$enabledNow, parsed=$supported")
            .that(!enabledNow || supported == true).isTrue()
        if (!enabledNow) assertThat(blurAvailability(Build.VERSION.SDK_INT, false, supported)).isNotEqualTo(BlurAvailability.AVAILABLE)
    }
}
