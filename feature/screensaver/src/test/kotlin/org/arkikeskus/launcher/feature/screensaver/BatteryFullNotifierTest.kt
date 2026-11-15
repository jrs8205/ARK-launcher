package org.arkikeskus.launcher.feature.screensaver

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FullChargeMarkTest {
    private val prefs get() = DeviceLocalFlags.prefs(RuntimeEnvironment.getApplication())
    private val mark by lazy { FullChargeMark(prefs) }

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

    @Test
    fun `the mark outlives the process that set it`() {
        mark.onState(full = true)

        // What the launcher's next process starts with: a new object over the same file.
        val afterRestart = FullChargeMark(prefs)

        assertThat(afterRestart.isSet).isTrue()
        assertThat(afterRestart.onState(full = true)).isFalse()
    }

    @Test
    fun `a cleared mark is cleared for the next process too`() {
        mark.onState(full = true)
        mark.clear()

        assertThat(FullChargeMark(prefs).isSet).isFalse()
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

    /** The system's battery broadcast, as every receiver registered for it hears it. */
    private fun battery(level: Int, plugged: Boolean = true, status: Int = BatteryManager.BATTERY_STATUS_CHARGING) {
        @Suppress("DEPRECATION") // the system's own battery broadcast is a sticky one
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, level)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, status)
                .putExtra(BatteryManager.EXTRA_PLUGGED, if (plugged) BatteryManager.BATTERY_PLUGGED_AC else 0),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun batteryWatchers(): Int =
        shadowOf(context).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) }

    /** What a process that told a full charge and then died leaves behind: the mark on disk, nobody listening. */
    private fun aMarkLeftByADeadProcess() {
        DeviceLocalFlags.prefs(context).edit().putBoolean(DeviceLocalFlags.KEY_BATTERY_FULL_TOLD, true).commit()
    }

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
        shadowOf(Looper.getMainLooper()).idle()
        notifier.onChargeState(full = true)

        assertThat(posted()).hasSize(1)
    }

    // --- The mark outlives the process -----------------------------------------------------------------

    @Test
    fun `a restarted launcher does not tell the same full charge again`() {
        notifier.onChargeState(full = true)
        // The user swipes it away; Android restarts the launcher's process on a phone still resting at 100 %.
        manager.cancelAll()

        BatteryFullNotifier(context).onChargeState(full = true)

        assertThat(posted()).isEmpty()
    }

    // --- The battery is followed while a full charge is marked, screensaver or not -----------------------

    @Test
    fun `a battery that left full with the screensaver closed is told at its next full`() {
        battery(level = 100)
        notifier.onChargeState(full = true)
        manager.cancelAll()

        // The screensaver is closed and the phone is used on its charger: down to 99 %, and full again.
        battery(level = 99)
        battery(level = 100)
        // Nothing is told while the screensaver is closed.
        assertThat(posted()).isEmpty()

        // The screensaver starts on the full battery.
        notifier.onChargeState(full = true)

        assertThat(posted()).hasSize(1)
    }

    @Test
    fun `a battery that stays full with the screensaver closed is not told again`() {
        battery(level = 100)
        notifier.onChargeState(full = true)
        manager.cancelAll()

        // Battery broadcasts go on arriving at 100 % (the temperature changes, the status turns to full).
        battery(level = 100, status = BatteryManager.BATTERY_STATUS_FULL)
        battery(level = 100)
        notifier.onChargeState(full = true)

        assertThat(posted()).isEmpty()
    }

    @Test
    fun `the battery is listened to only while a full charge is marked`() {
        battery(level = 100)
        assertThat(batteryWatchers()).isEqualTo(0)

        notifier.onChargeState(full = true)
        assertThat(batteryWatchers()).isEqualTo(1)

        battery(level = 99)
        assertThat(batteryWatchers()).isEqualTo(0)
    }

    @Test
    fun `unplugging ends the listening too`() {
        battery(level = 100)
        notifier.onChargeState(full = true)

        notifier.onPowerDisconnected()

        assertThat(batteryWatchers()).isEqualTo(0)
    }

    @Test
    fun `a restarted launcher learns at once that the battery has left full meanwhile`() {
        aMarkLeftByADeadProcess()
        battery(level = 80)

        ScreensaverPowerEvents(context, ChargeStart(context), notifier).start()
        shadowOf(Looper.getMainLooper()).idle()
        // Later the battery is full again and the screensaver starts.
        notifier.onChargeState(full = true)

        assertThat(posted()).hasSize(1)
    }

    @Test
    fun `a restarted launcher learns that the charger was taken away meanwhile`() {
        aMarkLeftByADeadProcess()
        // Unplugged at 100 %: the level alone would not say that the charge has ended.
        battery(level = 100, plugged = false, status = BatteryManager.BATTERY_STATUS_DISCHARGING)

        ScreensaverPowerEvents(context, ChargeStart(context), notifier).start()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(FullChargeMark(DeviceLocalFlags.prefs(context)).isSet).isFalse()
        assertThat(batteryWatchers()).isEqualTo(0)
    }

    @Test
    fun `a restarted launcher goes on listening for a full charge that is still marked`() {
        aMarkLeftByADeadProcess()
        battery(level = 100)

        ScreensaverPowerEvents(context, ChargeStart(context), notifier).start()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(batteryWatchers()).isEqualTo(1)
        // Still the same full charge: not told again.
        notifier.onChargeState(full = true)
        assertThat(posted()).isEmpty()
    }
}
