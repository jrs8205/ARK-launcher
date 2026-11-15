package org.arkikeskus.launcher.data.screensaver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The screensaver's settings as the backup sees them: a map of the known keys in their own types.
 * A value of another type, or a key this version does not know, is never written: a file from a
 * newer version or an edited one then falls back to the defaults instead of stopping the screensaver.
 */
@Singleton
class ScreensaverSettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs get() = context.screensaverPreferences()

    /** The known keys that are set, each in its own type. */
    fun exportRaw(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in prefs.all) {
            when {
                key in BOOLEAN_KEYS && value is Boolean -> out[key] = value
                key in INT_RANGES && value is Int -> out[key] = value
                key in STRING_KEYS && value is String -> out[key] = value
            }
        }
        return out
    }

    /** Writes the known keys of [values] that have the right type; the keys it does not name stay. */
    fun importRaw(values: Map<String, Any>) {
        val edit = prefs.edit()
        for ((key, value) in values) {
            val range = INT_RANGES[key]
            when {
                key in BOOLEAN_KEYS && value is Boolean -> edit.putBoolean(key, value)
                // JSON gives a number back as an Int, a Long or a Double.
                range != null && value is Number -> edit.putInt(key, value.toInt().coerceIn(range))
                key in STRING_KEYS && value is String -> edit.putString(key, value)
            }
        }
        edit.commit()
    }

    /** What [restore] needs to put the settings back as they are now. */
    fun snapshot(): Map<String, Any> = exportRaw()

    /** Makes the file exactly [snapshot]: what was set after it was taken is gone again. */
    fun restore(snapshot: Map<String, Any>) {
        // One edit: a clear is applied before the edit's own puts.
        val edit = prefs.edit().clear()
        for ((key, value) in snapshot) {
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is String -> edit.putString(key, value)
            }
        }
        edit.commit()
    }

    private companion object {
        val BOOLEAN_KEYS = setOf(
            DreamPreferences.KEY_LAYOUT_SWAPPED,
            DreamPreferences.KEY_FORCE_PORTRAIT,
            DreamPreferences.KEY_DIM_SCREENSAVER,
            DreamPreferences.KEY_SHOW_NOTIFICATIONS,
            DreamPreferences.KEY_VERTICAL_SHIFT_ONLY,
            DreamPreferences.KEY_SHOW_DEVICE_NAME,
            DreamPreferences.KEY_SHOW_SECONDS,
            DreamPreferences.KEY_SHOW_DATE,
            DreamPreferences.KEY_SHOW_ALARM,
            DreamPreferences.KEY_SHOW_BATTERY_DETAILS,
            DreamPreferences.KEY_SHOW_ESTIMATE,
            DreamPreferences.KEY_SHOW_CALENDAR,
            DreamPreferences.KEY_SHOW_CALENDAR_TITLES,
            DreamPreferences.KEY_OWN_BRIGHTNESS,
            DreamPreferences.KEY_START_ON_CHARGE,
            DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION,
            DreamPreferences.KEY_NIGHT_DIM,
            DreamPreferences.KEY_NIGHT_STYLE_AUTO,
        )

        /** The whole-number settings, each with the values it can take. */
        val INT_RANGES = mapOf(
            DreamPreferences.KEY_BRIGHTNESS_PERCENT to 1..100,
            DreamPreferences.KEY_NIGHT_DIM_START_MINUTES to 0..1439,
            DreamPreferences.KEY_NIGHT_DIM_END_MINUTES to 0..1439,
        )

        val STRING_KEYS = setOf(DreamPreferences.KEY_STYLE, DreamPreferences.KEY_ACCENT)
    }
}
