package org.arkikeskus.launcher.feature.screensaver

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeviceLocalFlagsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `a reading that milliamps could also give leaves the unit open`() {
        assertThat(DeviceLocalFlags.batteryCurrentMicroamps(context, 450)).isFalse()
    }

    @Test
    fun `a reading only microamps can give settles the unit for good`() {
        assertThat(DeviceLocalFlags.batteryCurrentMicroamps(context, 1_500_000)).isTrue()
        // The next reading is small again (the battery nearly full): the phone is still a microamp phone.
        assertThat(DeviceLocalFlags.batteryCurrentMicroamps(context, 450)).isTrue()
    }
}
