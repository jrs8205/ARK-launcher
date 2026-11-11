package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Nothing may go missing from Settings. Every row the page had in 1.0.5 is listed in [SettingsIndex] under an id,
 * every row carries its id as a tag, and these tests hold the two together: the list is the 1.0.5 one, each page
 * shows exactly its listed rows, in the listed order, in each state, and each tagged row is the row its entry names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SettingsInventoryTest {
    @get:Rule val compose = createComposeRule()
    private val res = RuntimeEnvironment.getApplication().resources

    /** Everything that makes a conditional row show is off, and the drawer has no colour layer and no picture. */
    private val everythingOff = LauncherSettings(
        showWeather = false,
        showStatusBar = false,
        showPageIndicator = false,
        homeGlassPanel = false,
        peopleBatchEnabled = false,
        useThemedIcons = false,
        doubleTapToLock = false,
        lockSoundsEnabled = false,
        blurBehindDrawer = false,
        drawerBackgroundColor = LauncherSettings.DRAWER_COLOR_TRANSPARENT,
    )

    /** Everything on, the drawer in the theme's colour. */
    private val everythingOn = LauncherSettings(
        showWeather = true,
        showStatusBar = true,
        showPageIndicator = true,
        homeGlassPanel = true,
        peopleBatchEnabled = true,
        useThemedIcons = true,
        doubleTapToLock = true,
        lockSoundsEnabled = true,
        blurBehindDrawer = true,
        drawerBackgroundColor = null,
    )

    private val picture = DrawerWallpaper(File.createTempFile("wallpaper", ".jpg").apply { deleteOnExit() }, luminance = 0.3f)

    private fun show(settings: LauncherSettings, wallpaper: DrawerWallpaper? = null) {
        compose.setContent {
            SettingsContent(
                s = settings,
                lists = SettingsLists(drawerWallpaper = wallpaper),
                actions = RecordedActions().actions,
                blurAvailability = BlurAvailability.AVAILABLE,
            )
        }
        compose.waitForIdle()
    }

    private val isSettingRow = SemanticsMatcher("is a settings row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("setting-") == true
    }

    /** The ids of the rows on the page, in the order they are laid out. */
    private fun shownIds(): List<String> =
        compose.onAllNodes(isSettingRow, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("setting-") }

    private val allIds = SettingsIndex.entries.map { it.id }
    private val unconditionalIds = SettingsIndex.entries.filter { it.fallback == null }.map { it.id }

    @Test fun theRegistryIsThe105Inventory() {
        val expected = listOf(
            "T2" to "settings_set_default",
            "H1" to "settings_columns", "H2" to "settings_rows", "H3" to "settings_show_labels",
            "H4" to "settings_two_line_labels", "H5" to "settings_show_folder_names", "H6" to "settings_folder_columns",
            "H7" to "settings_glass_panel", "H8" to "settings_glass_strength", "H9" to "settings_page_indicator",
            "H10" to "settings_home_page_marker", "H11" to "settings_page_switch_speed",
            "H12" to "settings_infinite_scroll", "H13" to "settings_lock_desktop",
            "D1" to "settings_dock_show", "D2" to "settings_dock_icons", "D3" to "settings_show_labels",
            "D4" to "settings_dock_bg", "D5" to "settings_dock_bottom_space",
            "A1" to "settings_columns", "A2" to "settings_show_labels", "A3" to "settings_two_line_labels",
            "A4" to "settings_label_color", "A5" to "settings_show_folder_names", "A6" to "settings_drawer_search",
            "Q1" to "settings_search_contacts", "A7" to "settings_frequent_apps", "A8" to "settings_hide_home_apps",
            "A9" to "settings_drawer_open_top", "A10" to "settings_drawer_color", "A11" to "settings_drawer_bg",
            "A12" to "settings_drawer_wallpaper", "A13" to "settings_drawer_picture_dim",
            "A14" to "settings_blur_behind_drawer", "A15" to "settings_blur_strength",
            "A16" to "settings_drawer_icon_size", "A17" to "settings_hidden_apps", "A18" to "settings_drawer_folders",
            "A19" to "settings_new_drawer_folder", "A20" to "settings_sort_drawer_folders",
            "A21" to "settings_mix_drawer_folders",
            "F1" to "settings_folder_app_names", "F2" to "settings_folder_icon_size", "F3" to "settings_folder_spacing",
            "F4" to "settings_folder_color", "F5" to "settings_folder_opacity",
            "H18" to "settings_show_weather", "C1" to "settings_clock_widget_color",
            "C2" to "settings_clock_widget_opacity", "C3" to "settings_clock_widget_text_size",
            "C4" to "settings_weather_icon", "C5" to "settings_weather_icon_size", "H19" to "settings_weather_app",
            "H20" to "settings_network_place_names", "H21" to "settings_notif_widget_count",
            "H22" to "settings_people_privacy", "H23" to "settings_people_show_apps", "H24" to "settings_people_batch",
            "H25" to "settings_people_batch_times", "H16" to "settings_widget_tonal", "H17" to "settings_widget_inset",
            "I1" to "settings_label_size", "I2" to "settings_icon_size", "I3" to "settings_label_color",
            "I4" to "settings_font", "I5" to "settings_themed_icons", "I6" to "settings_theme_all_icons",
            "I7" to "settings_icon_pack",
            "G1" to "settings_swipe_up_drawer", "G2" to "settings_swipe_down_notif",
            "G3" to "settings_folder_swipe_first_app", "G4" to "settings_left_edge", "G5" to "settings_double_tap_lock",
            "G5a" to "settings_double_tap_lock_grant", "G6" to "settings_long_press_delay",
            "G7" to "settings_icon_swipe_distance",
            "H14" to "settings_hide_status_bar", "H15" to "settings_hide_navigation_bar", "H26" to "settings_status_bar",
            "H27" to "settings_status_bar_scrim",
            "N1" to "settings_notif_dots", "N2" to "settings_notif_count", "N3" to "settings_notif_size",
            "S1" to "settings_lock_sounds", "S2" to "settings_lock_sound", "S3" to "settings_unlock_sound",
            "S4" to "settings_phone_sound_settings",
            "P1" to "settings_notif_access", "P2" to "settings_permission_calendar",
            "P3" to "settings_permission_contacts", "P4" to "settings_permission_phone",
            "P5" to "settings_permission_location",
            "B1" to "settings_backup",
            "U1" to "settings_updates_check", "FB1" to "settings_feedback_discussions",
            "FB2" to "settings_feedback_telegram", "FB3" to "settings_feedback_reddit",
            "E1" to "settings_support_title",
        )
        assertThat(SettingsIndex.entries.map { it.id to res.getResourceEntryName(it.title) })
            .containsExactlyElementsIn(expected).inOrder()
        assertThat(allIds).containsNoDuplicates()
    }

    @Test fun everyFallbackIsAnUnconditionalRowOfTheSameCategory() {
        SettingsIndex.entries.filter { it.fallback != null }.forEach { entry ->
            val fallback = SettingsIndex.entry(entry.fallback!!)
            assertWithMessage("${entry.id} falls back to ${fallback.id}").that(fallback.fallback).isNull()
            assertWithMessage("${entry.id} and ${fallback.id} are on the same page")
                .that(fallback.category).isEqualTo(entry.category)
        }
    }

    /** The ids of [category]'s rows (null = the main page) among [visible], in the registry's order. */
    private fun rowsOf(category: SettingsCategory?, visible: Collection<String>): List<String> =
        SettingsIndex.entries.filter { it.category == category && it.id in visible }.map { it.id }

    /** Walks every page and holds its rows, in order, against the registry's for that page. */
    private fun assertEveryPageShowsExactlyItsRows(visible: Collection<String>, eachRow: (SettingEntry) -> Unit = {}) {
        assertWithMessage("main page").that(shownIds()).containsExactlyElementsIn(rowsOf(null, visible)).inOrder()
        rowsOf(null, visible).forEach { eachRow(SettingsIndex.entry(it)) }
        for (category in SettingsCategory.entries) {
            // The backup category opens the backup screen itself (SettingsNavigationTest); it has no page of rows.
            if (category == SettingsCategory.BACKUP) continue
            compose.onNodeWithTag(categoryTag(category.key)).performScrollTo().performClick()
            compose.waitForIdle()
            assertWithMessage(category.key).that(shownIds()).containsExactlyElementsIn(rowsOf(category, visible)).inOrder()
            rowsOf(category, visible).forEach { eachRow(SettingsIndex.entry(it)) }
            compose.onNodeWithContentDescription(res.getString(R.string.settings_back)).performClick()
            compose.waitForIdle()
        }
    }

    @Test fun theBackupRowIsTheBackupCategoryItself() {
        assertThat(SettingsIndex.entries.filter { it.category == SettingsCategory.BACKUP }.map { it.id }).containsExactly("B1")
    }

    @Test fun withEverythingOffEachPageShowsItsUnconditionalRowsInOrder() {
        show(everythingOff)
        assertEveryPageShowsExactlyItsRows(unconditionalIds - "B1")
    }

    @Test fun withEverythingOnEachPageShowsAllItsRowsButThePictureDim() {
        show(everythingOn)
        assertEveryPageShowsExactlyItsRows(allIds - "A13" - "B1", eachRow = ::assertRowNamesItsTitle)
    }

    @Test fun withAPictureBehindTheDrawerItsDimShowsInPlaceOfTheColourOpacity() {
        show(everythingOn, wallpaper = picture)
        compose.onNodeWithTag(categoryTag(SettingsCategory.DRAWER.key)).performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(shownIds()).containsExactlyElementsIn(rowsOf(SettingsCategory.DRAWER, allIds - "A11")).inOrder()
        assertRowNamesItsTitle(SettingsIndex.entry("A13"))
    }

    private fun assertRowNamesItsTitle(entry: SettingEntry) {
        val title = res.getString(entry.title)
        compose.onNode(hasTestTag(settingTag(entry.id)) and hasAnyDescendant(hasText(title)), useUnmergedTree = true)
            .assertExists("row ${entry.id} does not show \"$title\"")
    }
}
