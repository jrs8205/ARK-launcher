package org.arkikeskus.launcher.feature.screensaver

import android.content.Context
import androidx.core.content.edit

/**
 * Facts about this phone rather than the user's choices. They live in a file of their own, apart from
 * the screensaver's settings, so a backup never carries them to another phone.
 */
internal object DeviceLocalFlags {

    private const val PREFS_NAME = "screensaver_device_local"

    /** This phone reports the battery current in µA (see [batteryCurrentUnitIsMicroamps]); a fact about the hardware. */
    const val KEY_BATTERY_CURRENT_MICROAMPS = "battery_current_microamps"

    /** Whether the unit is known to be µA, learning it from [currentRaw] when a reading settles it. */
    fun batteryCurrentMicroamps(context: Context, currentRaw: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_CURRENT_MICROAMPS, false)) return true
        if (!batteryCurrentUnitIsMicroamps(currentRaw)) return false
        prefs.edit { putBoolean(KEY_BATTERY_CURRENT_MICROAMPS, true) }
        return true
    }
}
