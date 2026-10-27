package org.arkikeskus.launcher

import android.app.ActivityOptions
import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A widget's configuration activity starts through an IntentSender the system creates, and from
 * Android 14 such a start counts as a background start unless the app that sends it grants its own
 * foreground privileges. Pixels tolerate the start; phones that enforce the rule to the letter
 * (Samsung One UI, #15) showed nothing — the call returned and the widget stayed unconfigured. The
 * launcher is in front whenever the user adds a widget, so the grant is its to give.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetConfigLaunchTest {
    @Test fun fromAndroid14TheLauncherGrantsTheStart() {
        val options = widgetConfigLaunchOptions(sdkInt = 34)

        assertThat(options).isNotNull()
        assertThat(options!!.pendingIntentBackgroundActivityStartMode)
            .isEqualTo(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }

    @Test fun before14ThereIsNothingToGrant() {
        assertThat(widgetConfigLaunchOptions(sdkInt = 33)).isNull()
    }
}
