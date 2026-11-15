package org.arkikeskus.launcher.feature.screensaver

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Facts about this phone rather than the user's choices. They live in a file of their own, apart from
 * the screensaver's settings, so a backup never carries them to another phone.
 */
internal object DeviceLocalFlags {

    private const val PREFS_NAME = "screensaver_device_local"

    /** This phone reports the battery current in µA (see [batteryCurrentUnitIsMicroamps]); a fact about the hardware. */
    const val KEY_BATTERY_CURRENT_MICROAMPS = "battery_current_microamps"

    /** The full charge the battery is in was already told with a notification (see [FullChargeMark]); a fact about this phone's battery now. */
    const val KEY_BATTERY_FULL_TOLD = "battery_full_told"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the unit is known to be µA, learning it from [currentRaw] when a reading settles it. */
    fun batteryCurrentMicroamps(context: Context, currentRaw: Int): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_BATTERY_CURRENT_MICROAMPS, false)) return true
        if (!batteryCurrentUnitIsMicroamps(currentRaw)) return false
        prefs.edit { putBoolean(KEY_BATTERY_CURRENT_MICROAMPS, true) }
        return true
    }
}
