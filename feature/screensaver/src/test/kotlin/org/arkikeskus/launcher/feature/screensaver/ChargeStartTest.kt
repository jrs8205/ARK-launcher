package org.arkikeskus.launcher.feature.screensaver

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/** The screensaver brought up as soon as charging begins: a short wake, and only when it has a use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChargeStartTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val power get() = context.getSystemService(PowerManager::class.java)
    private val ours get() = ComponentName(context, ScreensaverService::class.java).flattenToString()

    @Before fun aSleepingPhoneWithTheSwitchOn() {
        ShadowPowerManager.reset()
        context.screensaverPreferences().edit().clear().putBoolean(DreamPreferences.KEY_START_ON_CHARGE, true).commit()
        shadowOf(power).turnScreenOn(false)
        choose(enabled = "1", onCharge = "1", components = ours)
    }

    private fun choose(enabled: String?, onCharge: String?, components: String?) {
        val resolver = context.contentResolver
        Settings.Secure.putString(resolver, "screensaver_enabled", enabled)
        Settings.Secure.putString(resolver, "screensaver_activate_on_sleep", onCharge)
        Settings.Secure.putString(resolver, "screensaver_components", components)
    }

    private fun woken(): Boolean = ShadowPowerManager.getLatestWakeLock() != null

    @Test
    fun `the screen is woken when ours is the chosen screensaver`() {
        ChargeStart(context).onPowerConnected()

        assertThat(woken()).isTrue()
    }

    @Test
    fun `the screen is woken when the choice cannot be told`() {
        // Unset switches fall back to the phone's own defaults, which an app cannot read.
        choose(enabled = null, onCharge = null, components = ours)

        ChargeStart(context).onPowerConnected()

        assertThat(woken()).isTrue()
    }

    @Test
    fun `nothing happens with the switch off`() {
        context.screensaverPreferences().edit().putBoolean(DreamPreferences.KEY_START_ON_CHARGE, false).commit()

        ChargeStart(context).onPowerConnected()

        assertThat(woken()).isFalse()
    }

    @Test
    fun `nothing happens while the screen is on`() {
        shadowOf(power).turnScreenOn(true)

        ChargeStart(context).onPowerConnected()

        assertThat(woken()).isFalse()
    }

    @Test
    fun `nothing happens when another screensaver is the chosen one`() {
        choose(enabled = "1", onCharge = "1", components = "com.other/com.other.Dream")

        ChargeStart(context).onPowerConnected()

        assertThat(woken()).isFalse()
    }

    @Test
    fun `nothing happens when screensavers are switched off`() {
        choose(enabled = "0", onCharge = "1", components = ours)

        ChargeStart(context).onPowerConnected()

        assertThat(woken()).isFalse()
    }

    // The receiver that hears the charger.

    private fun powerReceivers(): Int = shadowOf(context).registeredReceivers.count {
        it.intentFilter.hasAction(Intent.ACTION_POWER_CONNECTED)
    }

    @Test
    fun `power connected reaches the charge start`() {
        ScreensaverPowerEvents(context, ChargeStart(context), BatteryFullNotifier(context)).start()

        context.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertThat(woken()).isTrue()
    }

    @Test
    fun `starting twice registers one receiver`() {
        val events = ScreensaverPowerEvents(context, ChargeStart(context), BatteryFullNotifier(context))

        events.start()
        events.start()

        assertThat(powerReceivers()).isEqualTo(1)
    }

    @Test
    fun `the receiver also hears the charger being taken away`() {
        ScreensaverPowerEvents(context, ChargeStart(context), BatteryFullNotifier(context)).start()

        val filter = shadowOf(context).registeredReceivers.single {
            it.intentFilter.hasAction(Intent.ACTION_POWER_CONNECTED)
        }.intentFilter

        assertThat(filter.hasAction(Intent.ACTION_POWER_DISCONNECTED)).isTrue()
    }

}
