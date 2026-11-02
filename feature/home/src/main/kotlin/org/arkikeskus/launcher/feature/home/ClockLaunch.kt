package org.arkikeskus.launcher.feature.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.util.Log

/** Clock apps to fall back on when no app answers SHOW_ALARMS: the AOSP clock and Google's. */
private val KNOWN_CLOCK_PACKAGES = listOf("com.android.deskclock", "com.google.android.deskclock")

/**
 * Opens the phone's clock app for a tap on the smartspace clock; returns whether something opened.
 *
 * The alarms view (SHOW_ALARMS) comes first. The AOSP clock on LineageOS and GrapheneOS guards it
 * with its SET_ALARM permission, which the launcher does not hold, so the start is refused there:
 * then the clock app itself opens, the one that handles SHOW_ALARMS (listed in the manifest's
 * queries) or, failing that, a known clock package. [start] is the activity start, replaceable
 * in tests.
 */
internal fun openClockApp(context: Context, start: (Intent) -> Unit = context::startActivity): Boolean {
    val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
    if (runCatching { start(showAlarms) }.isSuccess) return true
    val pm = context.packageManager
    val handler = pm.resolveActivity(showAlarms, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val candidates = listOfNotNull(handler) + KNOWN_CLOCK_PACKAGES
    for (packageName in candidates.distinct()) {
        val launch = pm.getLaunchIntentForPackage(packageName) ?: continue
        if (runCatching { start(launch) }.isSuccess) return true
    }
    Log.w("ClockLaunch", "No clock app opens: tried SHOW_ALARMS and ${candidates.distinct()}")
    return false
}
