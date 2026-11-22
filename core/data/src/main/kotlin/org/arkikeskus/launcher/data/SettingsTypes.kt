package org.arkikeskus.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The type each setting this launcher reads is kept in, by name, and the one place a stored value is held to
 * it ([typed]).
 *
 * A typed key hands over whatever is kept under its name, and the first use of a value of another type
 * throws. This version writes a setting it knows in its own type only, but an OLDER version kept a key it
 * did not know in the type its backup file gave it: a number as a Long, a text or a truth value as it stood.
 * The settings file a user upgrades with can therefore hold any setting added since in a type that is not
 * its own (a newer backup, or a hand-edited one, restored there). Read as it was, it threw at the home
 * screen's first reading of the settings, at every start.
 *
 * Every key the classes that read the settings file declare is here, with the type of its declaration; a
 * test reads the declarations and fails for one that is not. The profile bookkeeping is not: it is read by a
 * rule of its own, which also looks at the list (`ProfiledPreferences`).
 */
internal object SettingsTypes {
    enum class Kind { BOOLEAN, STRING, FLOAT, INT, LONG }

    private val kinds: Map<String, Kind> = buildMap {
        // What a backup carries, by the lists a restore writes it by.
        for (name in SettingsRepository.BOOLEAN_KEYS) put(name, Kind.BOOLEAN)
        for (name in SettingsRepository.STRING_KEYS) put(name, Kind.STRING)
        for (name in SettingsRepository.FLOAT_KEYS) put(name, Kind.FLOAT)
        for (name in SettingsRepository.INT_KEYS) put(name, Kind.INT)
        // This phone's own bookkeeping, which no backup carries and no restore writes.
        for (name in listOf("default_layout_seeded", "onboarding_done", "default_reminder_dismissed")) put(name, Kind.BOOLEAN)
        for (name in listOf("first_run_fresh", "people_held_notifications", "lock_sound_uri", "unlock_sound_uri")) put(name, Kind.STRING)
        put("chameleon_last_argb", Kind.INT)
        put("local_last_backup_time", Kind.LONG)
        // How often each app is opened, and the count of openings from elsewhere.
        put(AppUsageRepository.USAGE_KEY, Kind.STRING)
        put(AppUsageRepository.OWN_LAUNCHES_KEY, Kind.STRING)
        put(AppUsageRepository.SYSTEM_FRONT_KEY, Kind.STRING)
        put(AppUsageRepository.SYSTEM_SYNC_KEY, Kind.LONG)
        put(AppUsageRepository.COUNTING_PERIOD_KEY, Kind.LONG)
        put(AppUsageRepository.COUNTING_SINCE_KEY, Kind.LONG)
    }

    /** The home page and the page count: cut to the pages there can be before a number is turned into them. */
    private val PAGE_COUNTERS = setOf("home_page", "home_page_count")

    /** Colours: an ARGB value arrives as the signed Int's number or as its unsigned form, and wraps. */
    private val COLOURS = setOf(
        "app_label_color", "drawer_background_color", "drawer_label_color", "folder_background_color",
        "smartspace_background_color", "chameleon_last_argb",
    )

    /** The type the setting [name] is read in; null for a name that is no setting of this version. */
    fun kindOf(name: String): Kind? = kinds[name]

    /**
     * [values] with every setting of this version in its own type: a value of that type is left alone, a
     * number of another type is turned into it (what reads it clamps it), and anything else is left out, so
     * that the default applies. A name that is no setting of this version keeps whatever it holds: a newer
     * version's setting is carried along untouched. The same object comes back when nothing was off.
     */
    fun typed(values: Preferences): Preferences {
        var changed: MutablePreferences? = null
        for ((key, value) in values.asMap()) {
            val kind = kinds[key.name] ?: continue
            val own = inKind(key.name, kind, value)
            if (own === value) continue
            val out = changed ?: values.toMutablePreferences().also { changed = it }
            out.remove(key)
            @Suppress("UNCHECKED_CAST")
            if (own != null) out[key as Preferences.Key<Any>] = own
        }
        return changed?.toPreferences() ?: values
    }

    /** [value] itself when it is of [kind]; a number turned into [kind] when both are numbers; else null. */
    private fun inKind(name: String, kind: Kind, value: Any): Any? = when (kind) {
        Kind.BOOLEAN -> value as? Boolean
        Kind.STRING -> value as? String
        Kind.FLOAT -> value as? Float ?: (value as? Number)?.toFloat()
        Kind.LONG -> value as? Long ?: (value as? Number)?.toLong()
        Kind.INT -> value as? Int ?: (value as? Number)?.let { number ->
            when (name) {
                // Clamped before the conversion, so a huge number cannot wrap.
                in PAGE_COUNTERS -> number.toLong().coerceIn(0L, HomeLayoutRepository.MAX_PAGES.toLong()).toInt()
                in COLOURS -> number.toInt()
                else -> number.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            }
        }
    }
}

/**
 * [inner] read in the settings' own types ([SettingsTypes.typed]): what a reader is handed, and what a
 * change is worked out from. Nothing is written by the reading itself; the first change of anything leaves
 * the file without what was not handed over.
 */
internal class TypedPreferences(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
    override val data: Flow<Preferences> = inner.data.map(SettingsTypes::typed)

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        inner.updateData { transform(SettingsTypes.typed(it)) }
}
