package org.arkikeskus.launcher.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Whether the wallpaper blur behind the drawer can work here. Android's window blur needs Android 12
 * and a system that blurs across windows: a Fairphone 6 on Android 16 does not (its display stack
 * reports no background blur), so there the switch is greyed with the reason instead of silently doing
 * nothing. Battery saver also pauses cross-window blur, but only for the moment, so it does not grey
 * the switch.
 */
class BlurAvailabilityTest {

    @Test fun android11NeedsANewerSystemWhateverTheDisplayStackSays() {
        assertThat(blurAvailability(sdkInt = 30, crossWindowBlurEnabled = true, powerSaveMode = false))
            .isEqualTo(BlurAvailability.NEEDS_ANDROID_12)
        assertThat(blurAvailability(sdkInt = 30, crossWindowBlurEnabled = false, powerSaveMode = true))
            .isEqualTo(BlurAvailability.NEEDS_ANDROID_12)
    }

    @Test fun aSystemThatBlursAcrossWindowsIsAvailable() {
        assertThat(blurAvailability(sdkInt = 31, crossWindowBlurEnabled = true, powerSaveMode = false))
            .isEqualTo(BlurAvailability.AVAILABLE)
        assertThat(blurAvailability(sdkInt = 36, crossWindowBlurEnabled = true, powerSaveMode = true))
            .isEqualTo(BlurAvailability.AVAILABLE)
    }

    /** The Fairphone 6 case: Android 16, but the display stack never blurs behind a window. */
    @Test fun aDeviceWithoutCrossWindowBlurCannot() {
        assertThat(blurAvailability(sdkInt = 36, crossWindowBlurEnabled = false, powerSaveMode = false))
            .isEqualTo(BlurAvailability.DEVICE_CANNOT)
    }

    /** Battery saver switches cross-window blur off for the moment; the setting stays usable. */
    @Test fun batterySaverIsNotTheDevicesFault() {
        assertThat(blurAvailability(sdkInt = 36, crossWindowBlurEnabled = false, powerSaveMode = true))
            .isEqualTo(BlurAvailability.AVAILABLE)
    }
}
