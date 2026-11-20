package org.arkikeskus.launcher.feature.settings

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.feature.screensaver.R as ScreensaverR

/**
 * The pages of Settings, in the order the main page lists them: each with its [key] (kept in the saved state), its
 * name, the one line that says what is on it, and its icon.
 */
internal enum class SettingsCategory(
    val key: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
) {
    APPEARANCE(
        "appearance", R.string.settings_cat_appearance, R.string.settings_cat_appearance_summary, LauncherIcons.Palette,
    ),
    PROFILES("profiles", R.string.settings_cat_profiles, R.string.settings_cat_profiles_summary, LauncherIcons.Person),
    HOME("home", R.string.settings_home, R.string.settings_cat_home_summary, LauncherIcons.Home),
    DOCK("dock", R.string.settings_dock, R.string.settings_cat_dock_summary, LauncherIcons.Dock),
    DRAWER("drawer", R.string.settings_drawer, R.string.settings_cat_drawer_summary, LauncherIcons.Apps),
    FOLDERS("folders", R.string.settings_cat_folders, R.string.settings_cat_folders_summary, LauncherIcons.Folder),
    WIDGETS("widgets", R.string.settings_cat_widgets, R.string.settings_cat_widgets_summary, LauncherIcons.Widgets),
    ICONS("icons", R.string.settings_icons, R.string.settings_cat_icons_summary, LauncherIcons.Category),
    GESTURES("gestures", R.string.settings_gestures, R.string.settings_cat_gestures_summary, LauncherIcons.SwipeUp),
    BARS("bars", R.string.settings_cat_bars, R.string.settings_cat_bars_summary, LauncherIcons.SystemBars),
    NOTIFICATIONS(
        "notifications", R.string.settings_notifications, R.string.settings_cat_notifications_summary, LauncherIcons.Notifications,
    ),
    SOUNDS("sounds", R.string.settings_sounds, R.string.settings_lock_sounds, LauncherIcons.VolumeUp),
    SCREENSAVER(
        "screensaver", R.string.settings_cat_screensaver, R.string.settings_cat_screensaver_summary, LauncherIcons.Screensaver,
    ),
    PERMISSIONS("permissions", R.string.settings_permissions, R.string.settings_cat_permissions_summary, LauncherIcons.Shield),
    BACKUP("backup", R.string.settings_backup, R.string.settings_cat_backup_summary, LauncherIcons.Backup),
    ABOUT("about", R.string.settings_cat_about, R.string.settings_cat_about_summary, LauncherIcons.Info),
    ;

    companion object {
        /** The page saved under [key]; null (the main page) for no key and for one this version does not know. */
        fun fromKey(key: String?): SettingsCategory? = entries.firstOrNull { it.key == key }
    }
}

/** The tag a category's row carries on the main page. */
internal fun categoryTag(key: String): String = "category-$key"

/**
 * One row of Settings: its [id] (also the row's tag on the page, see [settingTag]), its [title] and static
 * [description], and the page it is on ([category]; null = the main page). A row that only shows while something
 * else is on names in [fallback] the row that is always there and controls it. [minSdk] is the Android version the
 * row needs and [maxSdk] the last one that has it; [needsPrivateSpace] says the row exists only where the launcher
 * sees a private space; [keywords] are further words to find it by. [perProfile] says the row's setting is one each
 * home profile has of its own (`HomeProfileKeys.PER_PROFILE`; a test holds the two against each other): with two or
 * more profiles such a row is marked.
 */
internal class SettingEntry(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int? = null,
    val category: SettingsCategory?,
    val fallback: String? = null,
    val minSdk: Int = 0,
    val keywords: List<Int> = emptyList(),
    val maxSdk: Int = Int.MAX_VALUE,
    val needsPrivateSpace: Boolean = false,
    val perProfile: Boolean = false,
) {
    /** Whether a phone on Android [sdkInt] has this row at all, given whether the launcher sees a private space
     *  there: the one rule for the page and for the search. */
    fun available(sdkInt: Int, privateSpaceSeen: Boolean): Boolean =
        sdkInt in minSdk..maxSdk && (!needsPrivateSpace || privateSpaceSeen)
}

/** The tag a row carries on the page. */
internal fun settingTag(id: String): String = "setting-$id"

/**
 * Every row of Settings, page by page in the order the rows are laid out: the one list the pages, the search and the
 * tests share. A row that is not here is not in Settings, and the inventory test fails when the two differ.
 */
internal object SettingsIndex {
    val entries: List<SettingEntry> = buildList {
        fun row(
            id: String,
            category: SettingsCategory?,
            @StringRes title: Int,
            @StringRes description: Int? = null,
            fallback: String? = null,
            minSdk: Int = 0,
            keywords: List<Int> = emptyList(),
            maxSdk: Int = Int.MAX_VALUE,
            needsPrivateSpace: Boolean = false,
        ) = add(
            SettingEntry(id, title, description, category, fallback, minSdk, keywords, maxSdk, needsPrivateSpace, id in PER_PROFILE_ROWS),
        )

        row("T2", null, R.string.settings_set_default)

        SettingsCategory.APPEARANCE.let { c ->
            row(
                "AP1", c, R.string.settings_base_colour, R.string.settings_base_colour_desc,
                keywords = listOf(R.string.settings_base_colour_keywords),
            )
            row("AP2", c, R.string.settings_charging_animation, R.string.settings_charging_animation_desc)
        }
        SettingsCategory.PROFILES.let { c ->
            row("PF1", c, R.string.settings_profile_list)
            row("PF2", c, R.string.settings_profile_new)
        }

        SettingsCategory.HOME.let { c ->
            row("H1", c, R.string.settings_columns)
            row("H2", c, R.string.settings_rows)
            row("H3", c, R.string.settings_show_labels)
            row("H4", c, R.string.settings_two_line_labels)
            row("H7", c, R.string.settings_glass_panel, R.string.settings_glass_panel_desc)
            row("H8", c, R.string.settings_glass_strength, fallback = "H7")
            row("H9", c, R.string.settings_page_indicator)
            row("H10", c, R.string.settings_home_page_marker, fallback = "H9")
            row("H11", c, R.string.settings_page_switch_speed)
            row("H12", c, R.string.settings_infinite_scroll, R.string.settings_infinite_scroll_desc)
            row(
                "H28", c, R.string.settings_wallpaper_scroll, R.string.settings_wallpaper_scroll_desc,
                keywords = listOf(R.string.settings_wallpaper_scroll_keywords),
            )
            row("H13", c, R.string.settings_lock_desktop)
        }
        SettingsCategory.DOCK.let { c ->
            row("D1", c, R.string.settings_dock_show)
            row("D2", c, R.string.settings_dock_icons)
            row("D3", c, R.string.settings_show_labels)
            row("D4", c, R.string.settings_dock_bg)
            row("D5", c, R.string.settings_dock_bottom_space)
        }
        SettingsCategory.DRAWER.let { c ->
            row("A1", c, R.string.settings_columns)
            row("A2", c, R.string.settings_show_labels)
            row("A3", c, R.string.settings_two_line_labels)
            row("A4", c, R.string.settings_label_color)
            row("A6", c, R.string.settings_drawer_search)
            // Android 15 alone: from Android 16 the launcher follows Android's own setting of this name. And
            // only where the launcher sees a private space (it is the default launcher and the phone has one).
            row(
                "A22", c, R.string.settings_hide_private_space, R.string.settings_hide_private_space_desc,
                fallback = "A6", minSdk = 35, maxSdk = 35, needsPrivateSpace = true,
            )
            row("A23", c, R.string.settings_drawer_search_bottom, R.string.settings_drawer_search_bottom_desc)
            row("Q1", c, R.string.settings_search_contacts)
            row("A7", c, R.string.settings_frequent_apps)
            row("A24", c, R.string.settings_count_system_usage, R.string.settings_count_system_usage_desc)
            row("A8", c, R.string.settings_hide_home_apps, R.string.settings_hide_home_apps_desc)
            row("A9", c, R.string.settings_drawer_open_top)
            row("A10", c, R.string.settings_drawer_color)
            row("A11", c, R.string.settings_drawer_bg, fallback = "A10")
            row("A12", c, R.string.settings_drawer_wallpaper, R.string.settings_drawer_wallpaper_desc)
            row("A13", c, R.string.settings_drawer_picture_dim, fallback = "A12")
            row("A14", c, R.string.settings_blur_behind_drawer, R.string.settings_blur_behind_drawer_desc)
            row("A15", c, R.string.settings_blur_strength, fallback = "A14")
            row("A16", c, R.string.settings_drawer_icon_size)
            row("A17", c, R.string.settings_hidden_apps)
        }
        // Every folder setting, wherever its folders are (1.2.1; a Telegram request). The rows that came from the
        // home screen's and the app drawer's pages keep the ids they had there (H5, H6, A5, A18 to A21).
        SettingsCategory.FOLDERS.let { c ->
            row("H5", c, R.string.settings_home_folder_names)
            row("A5", c, R.string.settings_drawer_folder_names)
            row("H6", c, R.string.settings_folder_columns)
            row("F1", c, R.string.settings_folder_app_names)
            row("F2", c, R.string.settings_folder_icon_size)
            row("F3", c, R.string.settings_folder_spacing)
            row("F4", c, R.string.settings_folder_color)
            row("F5", c, R.string.settings_folder_opacity)
            row("A18", c, R.string.settings_drawer_folders)
            row("A19", c, R.string.settings_new_drawer_folder, R.string.settings_new_drawer_folder_desc)
            row("A20", c, R.string.settings_sort_drawer_folders, R.string.settings_sort_drawer_folders_desc)
            row("A21", c, R.string.settings_mix_drawer_folders, R.string.settings_mix_drawer_folders_desc)
        }
        SettingsCategory.WIDGETS.let { c ->
            row("H18", c, R.string.settings_show_weather)
            row("C1", c, R.string.settings_clock_widget_color)
            row("C2", c, R.string.settings_clock_widget_opacity)
            row("C3", c, R.string.settings_clock_widget_text_size)
            row("C4", c, R.string.settings_weather_icon, fallback = "H18")
            row("C5", c, R.string.settings_weather_icon_size, fallback = "H18")
            row("H19", c, R.string.settings_weather_app, fallback = "H18")
            row("H20", c, R.string.settings_network_place_names, R.string.settings_network_place_names_desc, fallback = "H18")
            row("H21", c, R.string.settings_notif_widget_count)
            row("H22", c, R.string.settings_people_privacy)
            row("H23", c, R.string.settings_people_show_apps)
            row("H24", c, R.string.settings_people_batch)
            row("H25", c, R.string.settings_people_batch_times, fallback = "H24")
            row("H16", c, R.string.settings_widget_tonal)
            row("H17", c, R.string.settings_widget_inset, R.string.settings_widget_inset_desc)
        }
        SettingsCategory.ICONS.let { c ->
            row("I1", c, R.string.settings_label_size)
            row("I2", c, R.string.settings_icon_size)
            row("I3", c, R.string.settings_label_color)
            row("I4", c, R.string.settings_font, R.string.settings_font_desc)
            row("I5", c, R.string.settings_themed_icons, minSdk = Build.VERSION_CODES.TIRAMISU)
            row(
                "I6", c, R.string.settings_theme_all_icons, R.string.settings_theme_all_icons_desc,
                fallback = "I5", minSdk = Build.VERSION_CODES.TIRAMISU,
            )
            row("I7", c, R.string.settings_icon_pack)
            row("I8", c, R.string.settings_icon_shape, R.string.settings_icon_shape_desc)
        }
        SettingsCategory.GESTURES.let { c ->
            row("G1", c, R.string.settings_swipe_up_drawer)
            row("G2", c, R.string.settings_swipe_down_notif)
            row("G3", c, R.string.settings_folder_swipe_first_app, R.string.settings_folder_swipe_first_app_desc)
            row("G4", c, R.string.settings_left_edge)
            row("G5", c, R.string.settings_double_tap_lock)
            row("G5a", c, R.string.settings_double_tap_lock_grant, R.string.settings_double_tap_lock_grant_desc, fallback = "G5")
            row("G6", c, R.string.settings_long_press_delay, R.string.settings_long_press_delay_desc)
            row("G7", c, R.string.settings_icon_swipe_distance, R.string.settings_icon_swipe_distance_desc)
        }
        SettingsCategory.BARS.let { c ->
            row("H14", c, R.string.settings_hide_status_bar)
            row("H15", c, R.string.settings_hide_navigation_bar, R.string.settings_hide_navigation_bar_desc)
            row("H26", c, R.string.settings_status_bar)
            row("H27", c, R.string.settings_status_bar_scrim, fallback = "H26")
        }
        SettingsCategory.NOTIFICATIONS.let { c ->
            row("N1", c, R.string.settings_notif_dots)
            row("N2", c, R.string.settings_notif_count)
            row("N3", c, R.string.settings_notif_size)
        }
        SettingsCategory.SOUNDS.let { c ->
            row("S1", c, R.string.settings_lock_sounds, R.string.settings_lock_sounds_desc)
            row("S2", c, R.string.settings_lock_sound, fallback = "S1")
            row("S3", c, R.string.settings_unlock_sound, fallback = "S1")
            row("S4", c, R.string.settings_phone_sound_settings, R.string.settings_phone_sound_settings_desc, fallback = "S1")
        }
        // The screensaver's rows carry the screensaver module's own texts.
        SettingsCategory.SCREENSAVER.let { c ->
            row("SS1", c, ScreensaverR.string.dream_style_title, keywords = listOf(R.string.settings_screensaver_keywords))
            row("SS2", c, ScreensaverR.string.open_screensaver_settings, ScreensaverR.string.screensaver_charging_hint)
            row(
                "SS24", c, R.string.settings_screensaver_low_light, R.string.settings_screensaver_low_light_desc,
                minSdk = LOW_LIGHT_MODE_SDK,
            )
            row(
                "SS3", c, ScreensaverR.string.dream_start_on_charge_title, ScreensaverR.string.dream_start_on_charge_description,
            )
            row("SS4", c, R.string.settings_screensaver_battery_full, R.string.settings_screensaver_battery_full_desc)
            row("SS5", c, ScreensaverR.string.dream_device_name_title, ScreensaverR.string.dream_device_name_description)
            row("SS6", c, ScreensaverR.string.dream_show_seconds)
            row("SS7", c, ScreensaverR.string.dream_show_date)
            row("SS8", c, ScreensaverR.string.dream_show_alarm)
            row("SS9", c, ScreensaverR.string.dream_notifications_title, ScreensaverR.string.dream_notifications_description)
            row("SS10", c, ScreensaverR.string.dream_show_battery_details)
            row("SS11", c, ScreensaverR.string.dream_show_estimate)
            row("SS12", c, ScreensaverR.string.dream_accent_title)
            // The Gauges style's own rows.
            row("SS13", c, ScreensaverR.string.dream_calendar_title, ScreensaverR.string.dream_calendar_description, fallback = "SS1")
            row(
                "SS14", c, ScreensaverR.string.dream_calendar_titles_title, ScreensaverR.string.dream_calendar_titles_description,
                fallback = "SS1",
            )
            row("SS15", c, ScreensaverR.string.dream_flip_title, ScreensaverR.string.dream_flip_description)
            row("SS16", c, ScreensaverR.string.force_portrait_title, ScreensaverR.string.force_portrait_description)
            row("SS17", c, ScreensaverR.string.dream_vertical_shift_title, ScreensaverR.string.dream_vertical_shift_description)
            row("SS18", c, ScreensaverR.string.dim_screensaver_title, ScreensaverR.string.dim_screensaver_description)
            row("SS19", c, ScreensaverR.string.night_dim_title, ScreensaverR.string.night_dim_description)
            row("SS20", c, R.string.settings_screensaver_night_times, fallback = "SS19")
            row("SS21", c, ScreensaverR.string.dream_night_style_title, ScreensaverR.string.dream_night_style_description)
            row("SS22", c, ScreensaverR.string.dream_brightness_title, ScreensaverR.string.dream_brightness_description)
            row("SS23", c, R.string.settings_screensaver_brightness_level, fallback = "SS22")
        }
        SettingsCategory.PERMISSIONS.let { c ->
            row("P1", c, R.string.settings_notif_access)
            row(
                "P1a", c, R.string.settings_restricted_hint, R.string.settings_restricted_hint_desc,
                fallback = "P1", minSdk = Build.VERSION_CODES.TIRAMISU,
            )
            row("P2", c, R.string.settings_permission_calendar)
            row("P3", c, R.string.settings_permission_contacts)
            row("P4", c, R.string.settings_permission_phone)
            row("P5", c, R.string.settings_permission_location)
            // Android 13 made sending notifications a permission.
            row("P6", c, R.string.settings_permission_notifications, minSdk = Build.VERSION_CODES.TIRAMISU)
            row("P7", c, R.string.settings_permission_usage)
        }
        row("B1", SettingsCategory.BACKUP, R.string.settings_backup)
        SettingsCategory.ABOUT.let { c ->
            row("U1", c, R.string.settings_updates_check, R.string.settings_updates_check_desc)
            row("FB1", c, R.string.settings_feedback_discussions, R.string.settings_feedback_discussions_desc)
            row("FB2", c, R.string.settings_feedback_telegram, R.string.settings_feedback_telegram_desc)
            row("FB3", c, R.string.settings_feedback_reddit, R.string.settings_feedback_reddit_desc)
        }
        row(
            "E1", null, R.string.settings_support_title, R.string.settings_support_text,
            keywords = listOf(
                R.string.settings_support_github, R.string.settings_support_coffee, R.string.settings_support_paypal,
            ),
        )
    }

    private val byId: Map<String, SettingEntry> = entries.associateBy { it.id }

    /** The row with [id]. */
    fun entry(id: String): SettingEntry = byId.getValue(id)

    /** Whether the row [id] is one each home profile sets for itself; false for an id that is no row of Settings. */
    fun isPerProfile(id: String): Boolean = byId[id]?.perProfile == true
}

/**
 * The rows whose setting each home profile has of its own: what one sees (the grids, the look, the surfaces, the
 * drawer's content, the desktop lock). Every other row is how the phone behaves, the same in every profile. The
 * settings behind the rows are sorted in `HomeProfileKeys`, and a test fails when a row here and its setting there
 * disagree.
 */
private val PER_PROFILE_ROWS: Set<String> = setOf(
    "AP1",
    "H1", "H2", "H3", "H4", "H7", "H8", "H9", "H10", "H13",
    "D1", "D2", "D3", "D4", "D5",
    "A1", "A2", "A3", "A4", "A6", "A23", "A7", "A8", "A9", "A10", "A11", "A12", "A13", "A14", "A15", "A16",
    "H5", "A5", "H6", "F1", "F2", "F3", "F4", "F5", "A18", "A19", "A20", "A21",
    "C1", "C2", "C3", "C4", "C5", "H16", "H17",
    "I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8",
    "G4",
    "H14", "H15", "H26", "H27",
)
