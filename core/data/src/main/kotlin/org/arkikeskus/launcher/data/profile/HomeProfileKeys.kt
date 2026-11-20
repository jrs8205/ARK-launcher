package org.arkikeskus.launcher.data.profile

/**
 * Which stored settings a home profile owns, by preference name. A profile holds what the user sees (the
 * home screen's grids, the look, the surfaces, the drawer's folders, the desktop lock); how the phone
 * behaves (gestures, delays, sounds, notification dots, the apps' own names and icons) is shared by every
 * profile. Every key the settings declare is named on one side here, and a test fails for one that is not.
 */
object HomeProfileKeys {
    /** The home profile that is on (a Long; absent = the first profile). */
    const val ACTIVE = "home_profile_active"

    /** The home profile whose own values are kept in the settings file itself (a Long; absent = the first). */
    const val MAIN_STORE = "home_profile_main_store"

    /** The list of home profiles, as `HomeProfile.encode` writes it (absent = the one first profile). */
    const val LIST = "home_profiles"

    /** The highest home profile id ever given (a Long), so an id is never used twice. */
    const val LAST_ID = "home_profile_last_id"

    /** The profile bookkeeping itself: on neither side, never in a backup's settings, never set by an import. */
    val RESERVED: Set<String> = setOf(ACTIVE, MAIN_STORE, LIST, LAST_ID)

    /** What each home profile has of its own. */
    val PER_PROFILE: Set<String> = setOf(
        // The layout's own settings (the rows themselves carry their profile in the database).
        "home_page", "home_page_count", "folder_icons",
        // Grids.
        "home_columns", "home_rows", "dock_enabled", "dock_columns", "drawer_columns", "folder_columns",
        // The app drawer's content.
        "drawer_folders", "sort_drawer_folders", "mix_drawer_folders", "hide_home_apps_from_drawer",
        "show_frequent_apps", "show_drawer_search", "drawer_opens_at_top", "drawer_search_at_bottom",
        "left_swipe_app_key",
        // The look.
        "base_colour", "font_family", "use_themed_icons", "theme_all_icons", "icon_pack_package", "icon_shape",
        "icon_scale", "drawer_icon_scale", "app_label_scale", "app_label_color", "drawer_label_color",
        "show_dock_labels", "show_home_labels", "show_drawer_labels", "show_home_folder_names",
        "show_drawer_folder_names", "two_line_home_labels", "two_line_drawer_labels",
        // The surfaces.
        "dock_opacity", "dock_bottom_spacing", "drawer_opacity", "drawer_background_color", "drawer_picture_dim",
        "blur_behind_drawer", "drawer_blur_strength", "folder_show_labels", "folder_icon_scale", "folder_spacing",
        "folder_background_color", "folder_background_opacity", "home_glass_panel", "home_glass_opacity",
        "show_status_bar", "status_bar_scrim", "hide_system_status_bar", "hide_system_navigation_bar",
        "show_page_indicator", "home_page_marker", "smartspace_background_color", "smartspace_background_opacity",
        "smartspace_text_scale", "weather_icon_style", "weather_icon_large", "weather_icon_size", "widget_tonal_background",
        "widget_inset_like_icons",
        // The lock: one profile can be locked while the others are free.
        "desktop_locked",
        // A backup file holds one profile, so when one was last exported is that profile's own: a profile
        // that was never exported does not show another's time.
        "local_last_backup_time",
    )

    /**
     * Of [PER_PROFILE], what a copy of a profile does not take from its source: the profile's own bookkeeping.
     * A copy is a new profile, and it has never been exported.
     */
    val NOT_COPIED: Set<String> = setOf("local_last_backup_time")

    /** What every home profile shares. */
    val SHARED: Set<String> = setOf(
        // Gestures and their feel.
        "swipe_up_drawer", "swipe_down_notif", "folder_swipe_first_app", "long_press_scale", "icon_swipe_distance",
        "page_switch_speed", "infinite_scroll", "wallpaper_scroll", "double_tap_lock",
        // Sounds, charging, the private space.
        "lock_sounds_enabled", "lock_sound_uri", "unlock_sound_uri", "charging_animation",
        "hide_private_space_locked",
        // One wallpaper, so one colour followed.
        "chameleon_last_argb",
        // Notifications and people.
        "show_notif_dots", "notif_dot_count", "notif_dot_scale", "notif_widget_count_style", "people_privacy",
        "pinned_people", "people_aliases", "people_batch_enabled", "people_batch_times", "people_show_apps",
        "people_held_notifications",
        // Search and weather, and how "most used" is counted.
        "count_system_usage", "search_contacts", "show_weather", "network_place_names", "weather_app_key",
        // The apps' own names, icons and swipe actions: the same app looks the same in every profile.
        "hidden_apps", "custom_labels", "custom_icons", "shortcut_icons", "swipe_up_apps", "swipe_down_apps",
        // Settings' own state and this phone's bookkeeping.
        "collapsed_settings_sections", "default_layout_seeded", "onboarding_done",
        "default_reminder_dismissed", "first_run_fresh",
        // The dock of versions before 0.9.7, read once at a start and turned into rows.
        "dock_favorites",
    )

    /** Whether the setting stored under [name] belongs to each profile; a name this list does not know is shared. */
    fun isPerProfile(name: String): Boolean = name in PER_PROFILE
}
