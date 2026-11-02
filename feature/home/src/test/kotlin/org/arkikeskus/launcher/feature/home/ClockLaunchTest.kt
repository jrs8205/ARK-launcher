package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A tap on the smartspace clock opens the phone's clock app (Telegram report: on LineageOS and
 * GrapheneOS the AOSP clock answers SHOW_ALARMS only to apps holding its SET_ALARM permission, so
 * the start threw and nothing opened). The launcher first asks for the alarms view, and when that
 * is refused it opens the clock app itself: the one that handles SHOW_ALARMS, else a known clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ClockLaunchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val started = mutableListOf<Intent>()

    /** Installs [packageName] with a launcher activity, and when [handlesAlarms] an alarms view. */
    private fun installClock(packageName: String, handlesAlarms: Boolean) {
        val pm = shadowOf(context.packageManager)
        val main = ComponentName(packageName, "$packageName.Main")
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(
            main,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        if (handlesAlarms) {
            val alarms = ComponentName(packageName, "$packageName.Alarms")
            pm.addActivityIfNotPresent(alarms)
            pm.addIntentFilterForActivity(
                alarms,
                IntentFilter(AlarmClock.ACTION_SHOW_ALARMS).apply { addCategory(Intent.CATEGORY_DEFAULT) },
            )
        }
    }

    /** A start that records every intent and refuses the alarms view like a permission-guarded activity. */
    private fun refusingAlarms(intent: Intent) {
        started += intent
        if (intent.action == AlarmClock.ACTION_SHOW_ALARMS) throw SecurityException("Permission Denial")
    }

    @Test fun theAlarmsViewOpensWhenTheClockAllowsIt() {
        installClock("com.android.deskclock", handlesAlarms = true)

        val opened = openClockApp(context) { started += it }

        assertThat(opened).isTrue()
        assertThat(started.map { it.action }).containsExactly(AlarmClock.ACTION_SHOW_ALARMS)
    }

    @Test fun aRefusedAlarmsViewOpensTheClockAppThatHandlesIt() {
        installClock("com.android.deskclock", handlesAlarms = true)

        val opened = openClockApp(context, ::refusingAlarms)

        assertThat(opened).isTrue()
        val fallback = started.last()
        assertThat(fallback.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(fallback.`package` ?: fallback.component?.packageName).isEqualTo("com.android.deskclock")
    }

    @Test fun withoutAnAlarmsHandlerAKnownClockAppOpens() {
        installClock("com.google.android.deskclock", handlesAlarms = false)

        val opened = openClockApp(context, ::refusingAlarms)

        assertThat(opened).isTrue()
        val fallback = started.last()
        assertThat(fallback.`package` ?: fallback.component?.packageName).isEqualTo("com.google.android.deskclock")
    }

    @Test fun withoutAnyClockAppNothingOpensAndNothingCrashes() {
        val opened = openClockApp(context, ::refusingAlarms)

        assertThat(opened).isFalse()
        assertThat(started.map { it.action }).containsExactly(AlarmClock.ACTION_SHOW_ALARMS)
    }
}
