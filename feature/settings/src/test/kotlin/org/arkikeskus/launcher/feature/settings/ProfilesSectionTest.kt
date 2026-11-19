package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.data.profile.HomeProfileKeys
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings > Profiles: the home profiles listed, the one in use marked, a tap switching, a new one made as a
 * copy or empty, a profile renamed and deleted (asked first, never the last one). And with two or more
 * profiles, Settings says which one is being edited and marks every row that is set separately for each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ProfilesSectionTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val recorded = RecordedActions()

    private val unnamed = HomeProfile(HomeProfile.FIRST_ID, "")
    private val home = HomeProfile(1, "Home life")
    private val work = HomeProfile(2, "Work")

    @Before fun nothingRemembered() {
        SettingsScrollMemory.clear()
    }

    private fun text(id: Int, vararg args: Any) = res.getString(id, *args)

    /** Settings with [profiles], the one with [active]'s id in use, on the page [page] (null = the main page). */
    private fun show(profiles: List<HomeProfile>, active: Long = profiles.first().id, page: SettingsCategory? = SettingsCategory.PROFILES) {
        compose.setContent {
            SettingsContent(
                s = LauncherSettings(homeProfileId = active),
                lists = SettingsLists(homeProfiles = profiles),
                actions = recorded.actions,
                blurAvailability = BlurAvailability.AVAILABLE,
                openPage = page?.key,
            )
        }
        compose.waitForIdle()
    }

    private fun calls(name: String) = recorded.calls.filter { it.first == name }.map { it.second }

    @Test fun withOneProfileThePageListsItUnderItsDefaultName() {
        show(listOf(unnamed))

        compose.onNodeWithTag(profileRowTag(1)).assertIsSelected()
        compose.onNode(hasTestTag(profileRowTag(1)) and hasAnyDescendant(hasText(text(R.string.settings_profile_default_name))), useUnmergedTree = true)
            .assertExists()
    }

    @Test fun theProfileInUseIsMarkedAlsoForScreenReaders() {
        show(listOf(home, work), active = 2)

        compose.onNodeWithTag(profileRowTag(2)).assertIsSelected()
        compose.onNodeWithTag(profileRowTag(1)).assertIsNotSelected()
        // In words too, on the row it is true of only.
        compose.onNode(hasTestTag(profileRowTag(2)) and hasAnyDescendant(hasText(text(R.string.settings_profile_active))), useUnmergedTree = true)
            .assertExists()
        compose.onNode(hasTestTag(profileRowTag(1)) and hasAnyDescendant(hasText(text(R.string.settings_profile_active))), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test fun tappingAProfileSwitchesToIt() {
        show(listOf(home, work), active = 1)

        compose.onNodeWithTag(profileRowTag(2)).performClick()

        assertThat(calls("switchHomeProfile")).containsExactly(listOf(2L))
    }

    @Test fun tappingTheProfileInUseDoesNothing() {
        show(listOf(home, work), active = 1)

        compose.onNodeWithTag(profileRowTag(1)).performClick()

        assertThat(recorded.calls).isEmpty()
    }

    @Test fun aNewProfileIsACopyOfTheCurrentOneUnlessEmptyIsChosen() {
        show(listOf(home))
        compose.onNodeWithText(text(R.string.settings_profile_new)).performClick()
        // The line that says what a profile is, and is not.
        compose.onNodeWithText(text(R.string.settings_profile_explain)).assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("Work")
        compose.onNodeWithText(text(R.string.settings_profile_create)).performClick()

        compose.onNodeWithText(text(R.string.settings_profile_new)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Evening")
        compose.onNodeWithText(text(R.string.settings_profile_empty)).performClick()
        compose.onNodeWithText(text(R.string.settings_profile_create)).performClick()

        assertThat(calls("createHomeProfile")).containsExactly(listOf("Work", true), listOf("Evening", false)).inOrder()
    }

    /** A name that runs over its limit in the middle of an emoji loses the whole emoji, in the field already:
     *  half of one is a box on screen and no text to keep. */
    @Test fun theNameFieldNeverKeepsHalfAnEmoji() {
        show(listOf(home))
        compose.onNodeWithText(text(R.string.settings_profile_new)).performClick()

        compose.onNode(hasSetTextAction()).performTextReplacement("x".repeat(23) + "😀" + "y")

        val shown = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertThat(shown).isEqualTo("x".repeat(23))
        compose.onNodeWithText(text(R.string.settings_profile_create)).performClick()
        assertThat(calls("createHomeProfile")).containsExactly(listOf("x".repeat(23), true))
    }

    @Test fun aProfileIsRenamed() {
        show(listOf(home, work))
        compose.onNodeWithContentDescription(text(R.string.settings_profile_edit, "Work")).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Office")
        compose.onNodeWithText(text(R.string.settings_people_batch_times_save)).performClick()

        assertThat(calls("renameHomeProfile")).containsExactly(listOf(2L, "Office"))
    }

    @Test fun deletingAsksFirstAndNamesTheProfile() {
        show(listOf(home, work))
        compose.onNodeWithContentDescription(text(R.string.settings_profile_edit, "Work")).performClick()
        compose.onNodeWithText(text(R.string.settings_profile_delete)).performClick()

        assertThat(calls("deleteHomeProfile")).isEmpty()
        compose.onNodeWithText(text(R.string.settings_profile_delete_confirm, "Work")).assertExists()
        compose.onNodeWithText(text(R.string.settings_folder_delete_confirm)).performClick()

        assertThat(calls("deleteHomeProfile")).containsExactly(listOf(2L))
    }

    @Test fun theLastProfileHasNoDelete() {
        show(listOf(home))
        compose.onNodeWithContentDescription(text(R.string.settings_profile_edit, "Home life")).performClick()

        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNodeWithText(text(R.string.settings_profile_delete)).assertDoesNotExist()
    }

    @Test fun theEighthProfileDisablesNewProfile() {
        show((1L..8L).map { HomeProfile(it, "Profile $it") })

        compose.onNodeWithTag(settingTag("PF2")).performScrollTo()
        compose.onNodeWithText(text(R.string.settings_profile_limit)).assertExists()
        compose.onNodeWithTag(NEW_PROFILE_TAG).assertIsNotEnabled()
    }

    // --- Which profile is edited, and which rows are a profile's own -------------------------------------------

    private val mark get() = text(R.string.settings_profile_row_mark)

    private fun rowIsMarked(id: String): Boolean = compose
        .onAllNodes(hasTestTag(settingTag(id)) and hasContentDescription(mark), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    private fun open(category: SettingsCategory) {
        compose.onNodeWithTag(categoryTag(category.key)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun withTwoProfilesSettingsSaysWhichOneIsEditedAndMarksItsOwnRows() {
        show(listOf(home, work), active = 2, page = null)

        compose.onNodeWithText(text(R.string.settings_profile_editing, "Work")).assertExists()
        open(SettingsCategory.HOME)
        // The columns are the profile's own; the page speed is how the phone behaves, the same in every profile.
        assertThat(rowIsMarked("H1")).isTrue()
        assertThat(rowIsMarked("H11")).isFalse()
        // What the mark means is written out on a page that has one.
        compose.onNodeWithTag(PROFILE_MARK_LEGEND_TAG).assertExists()
    }

    @Test fun withOneProfileNothingIsSaidOrMarked() {
        show(listOf(home), page = null)

        compose.onNodeWithText(text(R.string.settings_profile_editing, "Home life")).assertDoesNotExist()
        open(SettingsCategory.HOME)
        assertThat(rowIsMarked("H1")).isFalse()
        compose.onNodeWithTag(PROFILE_MARK_LEGEND_TAG).assertDoesNotExist()
    }

    @Test fun aPageWithNoRowOfAProfilesOwnHasNoLegend() {
        show(listOf(home, work), page = null)

        open(SettingsCategory.NOTIFICATIONS)

        compose.onNodeWithTag(PROFILE_MARK_LEGEND_TAG).assertDoesNotExist()
    }

    @Test fun anUnnamedFirstProfileIsEditedUnderItsDefaultName() {
        show(listOf(unnamed, work), active = 1, page = null)

        compose.onNodeWithText(text(R.string.settings_profile_editing, text(R.string.settings_profile_default_name))).assertExists()
    }

    /**
     * The stored settings each row writes, by their names in the settings file (none for a row that stores
     * nothing there). A row is marked as a profile's own exactly when its settings are (`HomeProfileKeys`),
     * so a setting moved to the other side, or a new row, cannot leave the mark saying something else.
     */
    private val writes: Map<String, List<String>> = mapOf(
        "T2" to emptyList(),
        "AP1" to listOf("base_colour"), "AP2" to listOf("charging_animation"),
        "PF1" to emptyList(), "PF2" to emptyList(),
        "H1" to listOf("home_columns"), "H2" to listOf("home_rows"), "H3" to listOf("show_home_labels"),
        "H4" to listOf("two_line_home_labels"), "H7" to listOf("home_glass_panel"), "H8" to listOf("home_glass_opacity"),
        "H9" to listOf("show_page_indicator"), "H10" to listOf("home_page_marker"), "H11" to listOf("page_switch_speed"),
        "H12" to listOf("infinite_scroll"), "H28" to listOf("wallpaper_scroll"), "H13" to listOf("desktop_locked"),
        "D1" to listOf("dock_enabled"), "D2" to listOf("dock_columns"), "D3" to listOf("show_dock_labels"),
        "D4" to listOf("dock_opacity"), "D5" to listOf("dock_bottom_spacing"),
        "A1" to listOf("drawer_columns"), "A2" to listOf("show_drawer_labels"), "A3" to listOf("two_line_drawer_labels"),
        "A4" to listOf("drawer_label_color"), "A6" to listOf("show_drawer_search"), "A22" to listOf("hide_private_space_locked"),
        "Q1" to listOf("search_contacts"), "A7" to listOf("show_frequent_apps"), "A8" to listOf("hide_home_apps_from_drawer"),
        "A9" to listOf("drawer_opens_at_top"), "A10" to listOf("drawer_background_color"), "A11" to listOf("drawer_opacity"),
        "A12" to emptyList(), "A13" to listOf("drawer_picture_dim"), "A14" to listOf("blur_behind_drawer"),
        "A15" to listOf("drawer_blur_strength"), "A16" to listOf("drawer_icon_scale"), "A17" to listOf("hidden_apps"),
        "H5" to listOf("show_home_folder_names"), "A5" to listOf("show_drawer_folder_names"), "H6" to listOf("folder_columns"),
        "F1" to listOf("folder_show_labels"), "F2" to listOf("folder_icon_scale"), "F3" to listOf("folder_spacing"),
        "F4" to listOf("folder_background_color"), "F5" to listOf("folder_background_opacity"),
        "A18" to listOf("drawer_folders"), "A19" to listOf("drawer_folders"), "A20" to listOf("sort_drawer_folders"),
        "A21" to listOf("mix_drawer_folders"),
        "H18" to listOf("show_weather"), "C1" to listOf("smartspace_background_color"),
        "C2" to listOf("smartspace_background_opacity"), "C3" to listOf("smartspace_text_scale"),
        "C4" to listOf("weather_icon_style"), "C5" to listOf("weather_icon_large"), "H19" to listOf("weather_app_key"),
        "H20" to listOf("network_place_names"), "H21" to listOf("notif_widget_count_style"), "H22" to listOf("people_privacy"),
        "H23" to listOf("people_show_apps"), "H24" to listOf("people_batch_enabled"), "H25" to listOf("people_batch_times"),
        "H16" to listOf("widget_tonal_background"), "H17" to listOf("widget_inset_like_icons"),
        "I1" to listOf("app_label_scale"), "I2" to listOf("icon_scale"), "I3" to listOf("app_label_color"),
        "I4" to listOf("font_family"), "I5" to listOf("use_themed_icons"), "I6" to listOf("theme_all_icons"),
        "I7" to listOf("icon_pack_package"), "I8" to listOf("icon_shape"),
        "G1" to listOf("swipe_up_drawer"), "G2" to listOf("swipe_down_notif"), "G3" to listOf("folder_swipe_first_app"),
        "G4" to listOf("left_swipe_app_key"), "G5" to listOf("double_tap_lock"), "G5a" to emptyList(),
        "G6" to listOf("long_press_scale"), "G7" to listOf("icon_swipe_distance"),
        "H14" to listOf("hide_system_status_bar"), "H15" to listOf("hide_system_navigation_bar"),
        "H26" to listOf("show_status_bar"), "H27" to listOf("status_bar_scrim"),
        "N1" to listOf("show_notif_dots"), "N2" to listOf("notif_dot_count"), "N3" to listOf("notif_dot_scale"),
        "S1" to listOf("lock_sounds_enabled"), "S2" to listOf("lock_sound_uri"), "S3" to listOf("unlock_sound_uri"),
        "S4" to emptyList(),
        "P1" to emptyList(), "P1a" to emptyList(), "P2" to emptyList(), "P3" to emptyList(), "P4" to emptyList(),
        "P5" to emptyList(), "P6" to emptyList(),
        "B1" to emptyList(),
        "U1" to emptyList(), "FB1" to emptyList(), "FB2" to emptyList(), "FB3" to emptyList(),
        "E1" to emptyList(),
    )

    /** Rows that are a profile's own although they write no setting: the picture behind the app drawer is a
     *  file each profile has for itself. */
    private val ownWithoutASetting = setOf("A12")

    @Test fun everyRowsMarkAgreesWithTheListOfAProfilesOwnSettings() {
        val known = HomeProfileKeys.PER_PROFILE + HomeProfileKeys.SHARED
        for (entry in SettingsIndex.entries) {
            // The screensaver keeps its settings in a file of its own, shared by every profile.
            if (entry.category == SettingsCategory.SCREENSAVER) {
                assertWithMessage("row ${entry.id}").that(entry.perProfile).isFalse()
                continue
            }
            val keys = writes[entry.id] ?: error("Row ${entry.id} is not in this test's table: name the settings it writes")
            assertWithMessage("settings of row ${entry.id} that the settings file does not have").that(keys - known).isEmpty()
            val own = keys.map(HomeProfileKeys::isPerProfile).toSet()
            assertWithMessage("row ${entry.id} writes a profile's own setting and a shared one").that(own.size).isAtMost(1)
            val expected = entry.id in ownWithoutASetting || own.singleOrNull() == true
            assertWithMessage("row ${entry.id} marked as a profile's own").that(entry.perProfile).isEqualTo(expected)
        }
        assertThat(writes.keys - SettingsIndex.entries.map { it.id }.toSet()).isEmpty()
    }
}
