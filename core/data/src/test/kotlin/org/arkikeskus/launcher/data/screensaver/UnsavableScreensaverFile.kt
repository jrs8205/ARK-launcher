package org.arkikeskus.launcher.data.screensaver

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * A context whose screensaver file cannot be written to disk. Every edit lands in memory and its commit then
 * answers false, which is what SharedPreferences does when the write fails (a full disk, say).
 */
internal class UnsavableScreensaverFile(base: Context) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val real = super.getSharedPreferences(name, mode)
        return if (name == DreamPreferences.PREFS_NAME) Unsavable(real) else real
    }

    private class Unsavable(private val real: SharedPreferences) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = UnsavableEditor(real.edit())
    }

    private class UnsavableEditor(private val real: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = also { real.putString(key, value) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = also { real.putStringSet(key, values) }
        override fun putInt(key: String, value: Int) = also { real.putInt(key, value) }
        override fun putLong(key: String, value: Long) = also { real.putLong(key, value) }
        override fun putFloat(key: String, value: Float) = also { real.putFloat(key, value) }
        override fun putBoolean(key: String, value: Boolean) = also { real.putBoolean(key, value) }
        override fun remove(key: String) = also { real.remove(key) }
        override fun clear() = also { real.clear() }
        override fun apply() = real.apply()

        override fun commit(): Boolean {
            real.commit()
            return false
        }
    }
}
