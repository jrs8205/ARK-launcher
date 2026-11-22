package org.arkikeskus.launcher.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.data.SettingsTypes.Kind
import org.junit.Test
import java.lang.reflect.ParameterizedType

/**
 * Review round 13. A typed key hands over whatever is kept under its name, and the first use of a value of
 * another type throws. The released 1.2.1 kept a key it did not know in the type its backup file gave it, so
 * the settings file a user upgrades with can hold any setting this version added in a type that is not its
 * own. Every setting that is read has its type by name here, and a stored value is held to it in one place.
 */
class SettingsTypesTest {

    /** Every key the two classes that read the settings file declare, with the type its declaration gives it:
     *  read from the classes themselves, so a setting added later without a type fails here. */
    private fun declaredKeys(): Map<String, Kind> =
        listOf("SettingsRepository", "AppUsageRepository").flatMap { owner ->
            Class.forName("org.arkikeskus.launcher.data.$owner\$Keys").declaredFields
                .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
                .map { field ->
                    field.isAccessible = true
                    val kind = when (val type = (field.genericType as ParameterizedType).actualTypeArguments.single()) {
                        Boolean::class.javaObjectType -> Kind.BOOLEAN
                        String::class.java -> Kind.STRING
                        Float::class.javaObjectType -> Kind.FLOAT
                        Int::class.javaObjectType -> Kind.INT
                        Long::class.javaObjectType -> Kind.LONG
                        else -> error("a key of a type the settings do not keep: $type")
                    }
                    (field.get(null) as Preferences.Key<*>).name to kind
                }
        }.toMap()

    @Test fun everyKeyThatIsReadHasTheTypeItIsDeclaredWith() {
        val declared = declaredKeys()
        // The reading above found the keys at all.
        assertThat(declared.size).isAtLeast(110)

        for ((name, kind) in declared) assertWithMessage(name).that(SettingsTypes.kindOf(name)).isEqualTo(kind)
    }

    @Test fun aValueOfAnotherTypeIsNotHandedOver() {
        val typed = SettingsTypes.typed(
            preferencesOf(
                stringPreferencesKey("search_hidden_apps") to "yes",
                longPreferencesKey("count_system_usage") to 1L,
                booleanPreferencesKey("weather_icon_size") to true,
                stringPreferencesKey("app_usage_counting_since") to "now",
                booleanPreferencesKey("app_usage") to true,
                stringPreferencesKey("dock_opacity") to "half",
                // In their own type: kept.
                booleanPreferencesKey("drawer_search_at_bottom") to true,
                stringPreferencesKey("base_colour") to "blue",
            ),
        )

        assertThat(typed.asMap().mapKeys { it.key.name })
            .containsExactly("drawer_search_at_bottom", true, "base_colour", "blue")
    }

    @Test fun aNumberOfAnotherTypeIsTurnedIntoItsOwn() {
        val typed = SettingsTypes.typed(
            preferencesOf(
                longPreferencesKey("long_press_scale") to 2L,
                longPreferencesKey("drawer_columns") to 5L,
                // A page counter is cut to the pages there can be before it is turned, so a large one cannot wrap.
                longPreferencesKey("home_page_count") to Long.MAX_VALUE,
                // A colour arrives as the signed number or as its unsigned form: it wraps.
                longPreferencesKey("drawer_label_color") to 0xFFFFFFFFL,
                longPreferencesKey("dock_bottom_spacing") to Long.MAX_VALUE,
                intPreferencesKey("local_last_backup_time") to 7,
            ),
        )

        assertThat(typed[floatPreferencesKey("long_press_scale")]).isEqualTo(2.0f)
        assertThat(typed[intPreferencesKey("drawer_columns")]).isEqualTo(5)
        assertThat(typed[intPreferencesKey("home_page_count")]).isEqualTo(HomeLayoutRepository.MAX_PAGES)
        assertThat(typed[intPreferencesKey("drawer_label_color")]).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(typed[intPreferencesKey("dock_bottom_spacing")]).isEqualTo(Int.MAX_VALUE)
        assertThat(typed[longPreferencesKey("local_last_backup_time")]).isEqualTo(7L)
        assertThat(typed.asMap().values.map { it::class }).containsExactly(
            Float::class, Int::class, Int::class, Int::class, Int::class, Long::class,
        )
    }

    @Test fun whatIsNotASettingOfThisVersionIsLeftAsItIs() {
        val stored = preferencesOf(
            // A newer version's setting, kept in the type a backup file gave it.
            longPreferencesKey("something_new") to 3L,
            stringPreferencesKey("something_else") to "text",
            // The profile bookkeeping is read by its own rule (ProfiledPreferences).
            stringPreferencesKey("home_profile_active") to "2",
        )

        assertThat(SettingsTypes.typed(stored)).isEqualTo(stored)
    }

    @Test fun valuesThatAreAllInTheirOwnTypeComeBackAsTheSameObject() {
        val stored = preferencesOf(
            booleanPreferencesKey("search_hidden_apps") to true,
            floatPreferencesKey("icon_scale") to 1.1f,
            longPreferencesKey("something_new") to 3L,
        )

        assertThat(SettingsTypes.typed(stored)).isSameInstanceAs(stored)
    }
}
