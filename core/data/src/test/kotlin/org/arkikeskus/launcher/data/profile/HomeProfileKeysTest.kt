package org.arkikeskus.launcher.data.profile

import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A home profile owns what the user sees; how the phone behaves is shared by every profile. Each stored
 * setting is on one side, by name. A setting added later without a side fails here, so none can end up
 * shared, or per profile, by accident.
 */
class HomeProfileKeysTest {

    /** The names of every key the settings declare, read from the class itself so the list cannot go stale. */
    private fun storedKeyNames(): Set<String> =
        Class.forName("org.arkikeskus.launcher.data.SettingsRepository\$Keys").declaredFields
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                (field.get(null) as Preferences.Key<*>).name
            }
            .toSet()

    @Test fun everyStoredKeyIsOnExactlyOneSideOfTheSplit() {
        val stored = storedKeyNames()
        // The reading above found the keys at all.
        assertThat(stored.size).isAtLeast(100)

        assertThat(HomeProfileKeys.PER_PROFILE + HomeProfileKeys.SHARED).containsExactlyElementsIn(stored)
        assertThat(HomeProfileKeys.PER_PROFILE.intersect(HomeProfileKeys.SHARED)).isEmpty()
    }

    @Test fun whatIsSeenIsPerProfileAndHowThePhoneBehavesIsShared() {
        for (name in listOf(
            "base_colour", "home_columns", "home_rows", "home_page", "desktop_locked", "drawer_folders", "folder_icons",
            "icon_pack_package", "dock_opacity", "show_status_bar", "left_swipe_app_key", "hide_home_apps_from_drawer",
        )) {
            assertThat(HomeProfileKeys.isPerProfile(name)).isTrue()
        }
        for (name in listOf(
            "long_press_scale", "swipe_up_drawer", "page_switch_speed", "double_tap_lock", "show_notif_dots",
            "hidden_apps", "custom_labels", "custom_icons", "shortcut_icons", "swipe_up_apps", "weather_app_key",
            "chameleon_last_argb", "charging_animation", "people_privacy", "onboarding_done", "local_last_backup_time",
        )) {
            assertThat(HomeProfileKeys.isPerProfile(name)).isFalse()
        }
    }

    @Test fun theReservedKeysAreOnNeitherSide() {
        assertThat(HomeProfileKeys.RESERVED).containsExactly(
            "home_profile_active", "home_profile_main_store", "home_profiles", "home_profile_last_id",
        )
        assertThat(HomeProfileKeys.RESERVED.intersect(HomeProfileKeys.PER_PROFILE + HomeProfileKeys.SHARED)).isEmpty()
    }

    @Test fun anUnknownNameIsShared() {
        // A key another part of the launcher keeps in the same file, or a newer version's setting.
        assertThat(HomeProfileKeys.isPerProfile("app_usage")).isFalse()
        assertThat(HomeProfileKeys.isPerProfile("something_new")).isFalse()
    }
}
