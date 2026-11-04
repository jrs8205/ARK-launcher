package org.arkikeskus.launcher.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Whether the wallpaper blur behind the drawer can work here, and whether it does right now. Android's
 * window blur needs Android 12 and a display stack that blurs across windows: a Fairphone 6 on Android
 * 16 does not (its stack reports no background blur), so there the switch is greyed with the reason
 * instead of silently doing nothing. The system also pauses cross-window blur for the moment (battery
 * saver, a tunnelled video, minimal post-processing, a developer option): a pause proves nothing about
 * the device, so it never greys the switch, and only the device's own "no" does.
 */
class BlurAvailabilityTest {
    /** The user's own picture is blurred inside the window (Android 12's RenderEffect), so only the Android version
     *  limits it: a device without cross-window blur, or a system pausing it, still blurs the picture. */
    @Test fun theOwnPictureNeedsOnlyAndroid12() {
        assertThat(BlurAvailability.NEEDS_ANDROID_12.forOwnPicture()).isEqualTo(BlurAvailability.NEEDS_ANDROID_12)
        assertThat(BlurAvailability.DEVICE_CANNOT.forOwnPicture()).isEqualTo(BlurAvailability.AVAILABLE)
        assertThat(BlurAvailability.PAUSED.forOwnPicture()).isEqualTo(BlurAvailability.AVAILABLE)
        assertThat(BlurAvailability.AVAILABLE.forOwnPicture()).isEqualTo(BlurAvailability.AVAILABLE)
    }


    @Test fun android11NeedsANewerSystemWhateverTheDisplayStackSays() {
        assertThat(blurAvailability(sdkInt = 30, crossWindowBlurEnabled = true, deviceSupportsBlur = true))
            .isEqualTo(BlurAvailability.NEEDS_ANDROID_12)
        assertThat(blurAvailability(sdkInt = 30, crossWindowBlurEnabled = false, deviceSupportsBlur = null))
            .isEqualTo(BlurAvailability.NEEDS_ANDROID_12)
    }

    /** A blur that is on proves the support, whatever the property says. */
    @Test fun aSystemBlurringRightNowIsAvailable() {
        assertThat(blurAvailability(sdkInt = 31, crossWindowBlurEnabled = true, deviceSupportsBlur = true))
            .isEqualTo(BlurAvailability.AVAILABLE)
        assertThat(blurAvailability(sdkInt = 36, crossWindowBlurEnabled = true, deviceSupportsBlur = null))
            .isEqualTo(BlurAvailability.AVAILABLE)
        assertThat(blurAvailability(sdkInt = 36, crossWindowBlurEnabled = true, deviceSupportsBlur = false))
            .isEqualTo(BlurAvailability.AVAILABLE)
    }

    /** The Fairphone 6 case: Android 16, the display stack never blurs behind a window -- in battery saver too. */
    @Test fun aDeviceWhoseStackSaysNoCannot() {
        assertThat(blurAvailability(sdkInt = 36, crossWindowBlurEnabled = false, deviceSupportsBlur = false))
            .isEqualTo(BlurAvailability.DEVICE_CANNOT)
    }

    /** A Pixel in battery saver, with a tunnelled video or the developer option on: paused, not broken. */
    @Test fun aSupportingDeviceWithTheBlurOffIsOnlyPaused() {
        assertThat(blurAvailability(sdkInt = 36, crossWindowBlurEnabled = false, deviceSupportsBlur = true))
            .isEqualTo(BlurAvailability.PAUSED)
    }

    /** With the property unreadable a blur that is off is a pause, never a verdict on the device. */
    @Test fun unknownSupportWithTheBlurOffIsAPauseNotAVerdict() {
        assertThat(blurAvailability(sdkInt = 36, crossWindowBlurEnabled = false, deviceSupportsBlur = null))
            .isEqualTo(BlurAvailability.PAUSED)
    }

    @Test fun thePropertyIsReadAsTheWindowManagerReadsIt() {
        assertThat(parseBlurSupport("1")).isTrue()
        assertThat(parseBlurSupport("true")).isTrue()
        assertThat(parseBlurSupport(" 0 ")).isFalse()
        assertThat(parseBlurSupport("false")).isFalse()
        assertThat(parseBlurSupport("")).isNull()
        assertThat(parseBlurSupport(null)).isNull()
        assertThat(parseBlurSupport("maybe")).isNull()
    }
}
