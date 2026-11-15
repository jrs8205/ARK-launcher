package org.arkikeskus.launcher.data.screensaver

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
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

    /** The known keys that are set, each in its own type. A setting still at its default has no key. */
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

    /**
     * What one [importRaw] did to each known key: the value it found and the value it left (null: no key,
     * the setting's default). [rollbackImport] takes exactly this back.
     */
    class Import internal constructor(
        internal val before: Map<String, Any?>,
        internal val after: Map<String, Any?>,
    )

    /**
     * Makes the settings what [values] says: its known keys of the right type are written, and every
     * other known key is removed, so that setting is at its default again. [exportRaw] names only the
     * keys that are set, which makes a missing key the backup's way of saying "the default".
     *
     * Throws when the file could not be saved; the settings are then as they were.
     */
    @Throws(IOException::class)
    fun importRaw(values: Map<String, Any>): Import {
        val stored = prefs.all
        val before = KNOWN_KEYS.associateWith { stored[it] }
        val after = KNOWN_KEYS.associateWith { key -> importable(key, values[key]) }
        val import = Import(before, after)
        val edit = prefs.edit()
        for ((key, value) in after) edit.write(key, value)
        if (!edit.commit()) {
            // A commit that fails has still changed the values in memory, and the next write that
            // succeeds would save them: a restore reported as failed would take effect after all.
            rollbackImport(import)
            throw IOException("The screensaver's settings could not be saved")
        }
        return import
    }

    /**
     * Undoes [import]: every key still holding the value the import left goes back to the value it had
     * before, or away. A key written since (the Screensaver page is usable while a restore runs) keeps
     * its newer value; putting a whole old snapshot back silently discarded it.
     */
    fun rollbackImport(import: Import) {
        val stored = prefs.all
        val edit = prefs.edit()
        for (key in KNOWN_KEYS) {
            if (stored[key] == import.after[key]) edit.write(key, import.before[key])
        }
        edit.commit()
    }

    /** [value] as the setting [key] can hold it, or null when it cannot. */
    private fun importable(key: String, value: Any?): Any? {
        val range = INT_RANGES[key]
        return when {
            key in BOOLEAN_KEYS && value is Boolean -> value
            // JSON gives a number back as an Int, a Long or a Double.
            range != null && value is Number -> value.toInt().coerceIn(range)
            key in STRING_KEYS && value is String -> value
            else -> null
        }
    }

    private fun SharedPreferences.Editor.write(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is String -> putString(key, value)
            // No value, or one of a type no setting has: the key goes, the setting is at its default.
            else -> remove(key)
        }
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

        /** Every key an import decides; a key this version does not know is never touched. */
        val KNOWN_KEYS: Set<String> = BOOLEAN_KEYS + INT_RANGES.keys + STRING_KEYS
    }
}
