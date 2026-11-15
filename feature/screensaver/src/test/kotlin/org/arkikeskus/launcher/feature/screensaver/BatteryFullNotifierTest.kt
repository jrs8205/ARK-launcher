package org.arkikeskus.launcher.feature.screensaver

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
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

class FullChargeMarkTest {
    private val mark = FullChargeMark()

    @Test
    fun `full is told once, however often it is seen`() {
        assertThat(mark.onState(full = true)).isTrue()
        assertThat(mark.onState(full = true)).isFalse()
        assertThat(mark.onState(full = true)).isFalse()
    }

    @Test
    fun `after the battery has left full, the next full is told again`() {
        mark.onState(full = true)
        assertThat(mark.onState(full = false)).isFalse()

        assertThat(mark.onState(full = true)).isTrue()
    }

    @Test
    fun `clearing tells the same full again`() {
        mark.onState(full = true)
        mark.clear()

        assertThat(mark.onState(full = true)).isTrue()
    }
}

/** The notification when the battery is full while the screensaver is up: behind its switch, once per full charge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryFullNotifierTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notifier by lazy { BatteryFullNotifier(context) }

    @Before fun theSwitchOnAndThePermissionHeld() {
        context.screensaverPreferences().edit().clear()
            .putBoolean(DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION, true).commit()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun `a full battery is told with a notification of its own channel`() {
        notifier.onChargeState(full = true)

        val notification = posted().single()
        assertThat(notification.channelId).isEqualTo("battery_full")
        assertThat(manager.getNotificationChannel("battery_full").name.toString())
            .isEqualTo(context.getString(R.string.battery_full_channel_name))
        assertThat(notification.extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo(context.getString(R.string.battery_full_notification_title))
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
    }

    @Test
    fun `a tap leads to the launcher's own home screen`() {
        notifier.onChargeState(full = true)

        val intent = shadowOf(posted().single().contentIntent).savedIntent
        assertThat(intent.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(intent.categories).contains(Intent.CATEGORY_HOME)
        assertThat(intent.`package`).isEqualTo(context.packageName)
    }

    @Test
    fun `nothing is posted with the switch off`() {
        context.screensaverPreferences().edit().putBoolean(DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION, false).commit()

        notifier.onChargeState(full = true)

        assertThat(posted()).isEmpty()
    }

    @Test
    fun `nothing is posted without the permission, and nothing throws`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier.onChargeState(full = true)

        assertThat(posted()).isEmpty()
    }

    @Test
    fun `the permission granted later tells the full battery that is still there`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.onChargeState(full = true)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.onChargeState(full = true)

        assertThat(posted()).hasSize(1)
    }

    @Test
    fun `one notification per full charge, not one per start of the screensaver`() {
        notifier.onChargeState(full = true)
        // The user swipes it away; the screensaver restarts on a phone that still rests at 100 %.
        manager.cancelAll()

        notifier.onChargeState(full = true)
        notifier.onChargeState(full = true)

        assertThat(posted()).isEmpty()
    }

    @Test
    fun `a battery that left full and filled again is told again`() {
        notifier.onChargeState(full = true)
        manager.cancelAll()

        notifier.onChargeState(full = false)
        notifier.onChargeState(full = true)

        assertThat(posted()).hasSize(1)
    }

    @Test
    fun `unplugging lets the next full charge be told`() {
        notifier.onChargeState(full = true)
        manager.cancelAll()

        // The charger was taken away while the screensaver was not up to see the battery leave full.
        notifier.onPowerDisconnected()
        notifier.onChargeState(full = true)

        assertThat(posted()).hasSize(1)
    }

    @Test
    fun `the receiver passes the charger being taken away on`() {
        ScreensaverPowerEvents(context, ChargeStart(context), notifier).start()
        notifier.onChargeState(full = true)
        manager.cancelAll()

        context.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        notifier.onChargeState(full = true)

        assertThat(posted()).hasSize(1)
    }
}
