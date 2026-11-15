package org.arkikeskus.launcher.data.screensaver

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreensaverSettingsStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.screensaverPreferences()
    private val store get() = ScreensaverSettingsStore(context)

    @Test
    fun `export gives only the keys that are set, in their own types`() {
        prefs.edit()
            .putString(DreamPreferences.KEY_STYLE, "ring")
            .putBoolean(DreamPreferences.KEY_SHOW_SECONDS, false)
            .putInt(DreamPreferences.KEY_BRIGHTNESS_PERCENT, 40)
            .commit()

        assertThat(store.exportRaw()).containsExactly(
            DreamPreferences.KEY_STYLE, "ring",
            DreamPreferences.KEY_SHOW_SECONDS, false,
            DreamPreferences.KEY_BRIGHTNESS_PERCENT, 40,
        )
    }

    @Test
    fun `export leaves out a key the file holds in another type`() {
        prefs.edit().putString(DreamPreferences.KEY_SHOW_DATE, "yes").commit()

        assertThat(store.exportRaw()).isEmpty()
    }

    @Test
    fun `import writes the known keys and ignores the rest`() {
        store.importRaw(
            mapOf(
                DreamPreferences.KEY_STYLE to "gauges",
                DreamPreferences.KEY_NIGHT_DIM to true,
                "unknown_key" to 1,
                // A known key in the wrong type is not written: the setting is at its default.
                DreamPreferences.KEY_SHOW_DATE to "yes",
                DreamPreferences.KEY_ACCENT to 7,
            ),
        )

        assertThat(prefs.all).containsExactly(
            DreamPreferences.KEY_STYLE, "gauges",
            DreamPreferences.KEY_NIGHT_DIM, true,
        )
    }

    @Test
    fun `a number that came back from JSON as a Long or a Double is stored as an Int`() {
        store.importRaw(
            mapOf(
                DreamPreferences.KEY_BRIGHTNESS_PERCENT to 40.0,
                DreamPreferences.KEY_NIGHT_DIM_START_MINUTES to 1320L,
            ),
        )

        assertThat(prefs.getInt(DreamPreferences.KEY_BRIGHTNESS_PERCENT, -1)).isEqualTo(40)
        assertThat(prefs.getInt(DreamPreferences.KEY_NIGHT_DIM_START_MINUTES, -1)).isEqualTo(1320)
    }

    @Test
    fun `a number outside its range is brought inside it`() {
        store.importRaw(
            mapOf(
                DreamPreferences.KEY_BRIGHTNESS_PERCENT to 400,
                DreamPreferences.KEY_NIGHT_DIM_START_MINUTES to -5,
                DreamPreferences.KEY_NIGHT_DIM_END_MINUTES to 99_999,
            ),
        )

        assertThat(prefs.getInt(DreamPreferences.KEY_BRIGHTNESS_PERCENT, -1)).isEqualTo(100)
        assertThat(prefs.getInt(DreamPreferences.KEY_NIGHT_DIM_START_MINUTES, -1)).isEqualTo(0)
        assertThat(prefs.getInt(DreamPreferences.KEY_NIGHT_DIM_END_MINUTES, -1)).isEqualTo(1439)
    }

    @Test
    fun `import puts the settings the map does not name back to their defaults`() {
        // Export leaves a setting at its default out, so a backup says "default" by not naming the key.
        prefs.edit()
            .putBoolean(DreamPreferences.KEY_SHOW_ALARM, false)
            .putBoolean(DreamPreferences.KEY_NIGHT_DIM, true)
            .putInt(DreamPreferences.KEY_BRIGHTNESS_PERCENT, 80)
            .commit()

        store.importRaw(mapOf(DreamPreferences.KEY_STYLE to "edge"))

        assertThat(prefs.all).containsExactly(DreamPreferences.KEY_STYLE, "edge")
    }

    @Test
    fun `importing what a phone at its defaults exported makes every setting the default`() {
        val atDefaults = store.exportRaw()
        prefs.edit().putBoolean(DreamPreferences.KEY_NIGHT_DIM, true).putString(DreamPreferences.KEY_STYLE, "ring").commit()

        store.importRaw(atDefaults)

        assertThat(atDefaults).isEmpty()
        assertThat(prefs.all).isEmpty()
    }

    @Test
    fun `a value of the wrong type puts its setting at the default instead of keeping the old one`() {
        prefs.edit().putBoolean(DreamPreferences.KEY_SHOW_DATE, false).commit()

        store.importRaw(mapOf(DreamPreferences.KEY_SHOW_DATE to "yes"))

        assertThat(prefs.contains(DreamPreferences.KEY_SHOW_DATE)).isFalse()
    }

    // --- An import that fails, or that has to be taken back ---------------------------------------------

    private fun nightStyleAndNoAlarm() {
        prefs.edit().putString(DreamPreferences.KEY_STYLE, "night").putBoolean(DreamPreferences.KEY_SHOW_ALARM, false).commit()
    }

    private val edgeAndStartOnCharge = mapOf<String, Any>(
        DreamPreferences.KEY_STYLE to "edge",
        DreamPreferences.KEY_START_ON_CHARGE to true,
    )

    @Test
    fun `an import that cannot be saved fails, and the settings are as they were`() {
        nightStyleAndNoAlarm()
        val unsavable = ScreensaverSettingsStore(UnsavableScreensaverFile(context))

        val failure = runCatching { unsavable.importRaw(edgeAndStartOnCharge) }

        assertThat(failure.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        // Not in memory either: the next write that does succeed would otherwise save the import after all.
        assertThat(prefs.all).containsExactly(
            DreamPreferences.KEY_STYLE, "night",
            DreamPreferences.KEY_SHOW_ALARM, false,
        )
    }

    @Test
    fun `taking an import back makes the file what it was`() {
        nightStyleAndNoAlarm()

        store.rollbackImport(store.importRaw(edgeAndStartOnCharge))

        assertThat(prefs.all).containsExactly(
            DreamPreferences.KEY_STYLE, "night",
            DreamPreferences.KEY_SHOW_ALARM, false,
        )
    }

    @Test
    fun `taking an import back keeps what was changed after it`() {
        nightStyleAndNoAlarm()
        val import = store.importRaw(edgeAndStartOnCharge)
        // The Screensaver page is open while the restore runs: the user picks a style and switches seconds off.
        prefs.edit().putString(DreamPreferences.KEY_STYLE, "ring").putBoolean(DreamPreferences.KEY_SHOW_SECONDS, false).commit()

        store.rollbackImport(import)

        assertThat(prefs.all).containsExactly(
            DreamPreferences.KEY_STYLE, "ring", // newer than the import: kept
            DreamPreferences.KEY_SHOW_SECONDS, false, // newer than the import: kept
            DreamPreferences.KEY_SHOW_ALARM, false, // removed by the import: back
            // start_on_charge, which the import added, is gone again
        )
    }

    @Test
    fun `the keys are the ones the screensaver has always stored`() {
        // A backup made by one version is read by the next: the names never change.
        assertThat(DreamPreferences.KEY_STYLE).isEqualTo("dream_style")
        assertThat(DreamPreferences.KEY_ACCENT).isEqualTo("accent_color")
        assertThat(DreamPreferences.KEY_BRIGHTNESS_PERCENT).isEqualTo("brightness_percent")
        assertThat(DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION).isEqualTo("battery_full_notification")
        assertThat(DreamPreferences.PREFS_NAME).isEqualTo("screensaver_preferences")
    }
}
