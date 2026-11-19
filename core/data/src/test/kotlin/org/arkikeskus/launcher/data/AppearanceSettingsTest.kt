package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Test

/**
 * The 1.0.5 appearance settings: the open folder's look, the clock/weather widget's look and the
 * glass panel behind the home grid. Each is off or neutral out of the box (an update changes
 * nothing by itself), keeps to its slider's range, and travels in the backup with its own type.
 */
class AppearanceSettingsTest {
    private fun newRepository() = SettingsRepository(InMemoryDataStore())
    private val blue = 0xFF42A5F5.toInt()

    @Test fun `the folder look defaults to today's and keeps to its ranges`() = runTest {
        val repo = newRepository()
        val defaults = repo.settings.first()
        assertThat(defaults.folderShowLabels).isTrue()
        assertThat(defaults.folderIconScale).isEqualTo(1f)
        assertThat(defaults.folderSpacing).isEqualTo(1f)
        assertThat(defaults.folderBackgroundColor).isNull()
        assertThat(defaults.folderBackgroundOpacity).isEqualTo(1f)

        repo.setFolderShowLabels(false)
        repo.setFolderIconScale(5f)
        repo.setFolderSpacing(0f)
        repo.setFolderBackgroundColor(blue)
        repo.setFolderBackgroundOpacity(0f)
        val set = repo.settings.first()
        assertThat(set.folderShowLabels).isFalse()
        assertThat(set.folderIconScale).isEqualTo(SettingsRepository.MAX_FOLDER_ICON_SCALE)
        assertThat(set.folderSpacing).isEqualTo(SettingsRepository.MIN_FOLDER_SPACING)
        assertThat(set.folderBackgroundColor).isEqualTo(blue)
        assertThat(set.folderBackgroundOpacity).isEqualTo(SettingsRepository.MIN_CARD_OPACITY)

        repo.setFolderBackgroundColor(null)
        assertThat(repo.settings.first().folderBackgroundColor).isNull()
    }

    @Test fun `the widget look defaults to today's and keeps to its ranges`() = runTest {
        val repo = newRepository()
        val defaults = repo.settings.first()
        assertThat(defaults.smartspaceBackgroundColor).isNull()
        assertThat(defaults.smartspaceBackgroundOpacity).isEqualTo(LauncherSettings.DEFAULT_SMARTSPACE_BACKGROUND)
        assertThat(defaults.smartspaceTextScale).isEqualTo(1f)
        assertThat(defaults.weatherIconStyle).isEqualTo(LauncherSettings.WEATHER_ICON_EMOJI)
        assertThat(defaults.weatherIconSize).isEqualTo(LauncherSettings.WEATHER_SIZE_STANDARD)

        repo.setSmartspaceBackgroundColor(blue)
        repo.setSmartspaceBackgroundOpacity(2f)
        repo.setSmartspaceTextScale(9f)
        repo.setWeatherIconStyle(LauncherSettings.WEATHER_ICON_ANIMATED)
        repo.setWeatherIconSize(LauncherSettings.WEATHER_SIZE_LARGE)
        val set = repo.settings.first()
        assertThat(set.smartspaceBackgroundColor).isEqualTo(blue)
        assertThat(set.smartspaceBackgroundOpacity).isEqualTo(1f)
        assertThat(set.smartspaceTextScale).isEqualTo(SettingsRepository.MAX_SMARTSPACE_TEXT_SCALE)
        assertThat(set.weatherIconStyle).isEqualTo(LauncherSettings.WEATHER_ICON_ANIMATED)
        // The size is its own setting: the moving icon can be large too.
        assertThat(set.weatherIconSize).isEqualTo(LauncherSettings.WEATHER_SIZE_LARGE)
        assertThat(LauncherSettings.WEATHER_ICON_STYLES).containsExactly(
            LauncherSettings.WEATHER_ICON_EMOJI, LauncherSettings.WEATHER_ICON_COLOR, LauncherSettings.WEATHER_ICON_ANIMATED,
        )

        // An unknown style (a newer version's backup, a typo) reads as the standard one.
        repo.setWeatherIconStyle("hologram")
        assertThat(repo.settings.first().weatherIconStyle).isEqualTo(LauncherSettings.WEATHER_ICON_EMOJI)
    }

    @Test fun `the glass panel is off out of the box and its strength keeps to its range`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().homeGlassPanel).isFalse()
        assertThat(repo.settings.first().homeGlassOpacity).isEqualTo(LauncherSettings.DEFAULT_GLASS_OPACITY)

        repo.setHomeGlassPanel(true)
        repo.setHomeGlassOpacity(3f)
        assertThat(repo.settings.first().homeGlassPanel).isTrue()
        assertThat(repo.settings.first().homeGlassOpacity).isEqualTo(SettingsRepository.MAX_GLASS_OPACITY)
        repo.setHomeGlassOpacity(0f)
        assertThat(repo.settings.first().homeGlassOpacity).isEqualTo(SettingsRepository.MIN_GLASS_OPACITY)
    }

    /** JSON has one number type: the registry gives every key back its own (a colour as an Int, even
     *  from its unsigned form; a scale as a Float even when the file says 1). */
    @Test fun `the appearance settings travel in the backup with their types`() = runTest {
        val repo = newRepository()
        repo.setFolderShowLabels(false)
        repo.setFolderIconScale(0.8f)
        repo.setFolderBackgroundColor(blue)
        repo.setSmartspaceBackgroundColor(blue)
        repo.setSmartspaceTextScale(1.2f)
        repo.setWeatherIconStyle(LauncherSettings.WEATHER_ICON_COLOR)
        repo.setWeatherIconSize(LauncherSettings.WEATHER_SIZE_LARGE)
        repo.setHomeGlassPanel(true)
        repo.setHideSystemNavigationBar(true)

        val exported = repo.exportRaw()
        assertThat(exported).containsEntry("folder_show_labels", false)
        assertThat(exported).containsEntry("folder_background_color", blue)
        assertThat(exported).containsEntry("weather_icon_style", LauncherSettings.WEATHER_ICON_COLOR)
        assertThat(exported).containsEntry("weather_icon_large", true)

        val restored = newRepository()
        restored.importRaw(exported)
        assertThat(restored.settings.first()).isEqualTo(repo.settings.first())

        val fromJson = newRepository()
        fromJson.importRaw(
            mapOf(
                "folder_icon_scale" to 1, "folder_spacing" to 1, "folder_background_opacity" to 1,
                "folder_background_color" to 0xFF42A5F5L, "smartspace_background_color" to 0xFF42A5F5L,
                "smartspace_background_opacity" to 1, "smartspace_text_scale" to 1, "home_glass_opacity" to 0.3,
            ),
        )
        val read = fromJson.settings.first()
        assertThat(read.folderIconScale).isEqualTo(1f)
        assertThat(read.folderBackgroundColor).isEqualTo(blue)
        assertThat(read.smartspaceBackgroundColor).isEqualTo(blue)
        assertThat(read.smartspaceBackgroundOpacity).isEqualTo(1f)
        assertThat(read.homeGlassOpacity).isEqualTo(0.3f)
    }

    /** The owner's word, 2026-11-19: the weather can be made larger still. A third size beside standard and
     *  large; what an older version stored (large or not, as a flag) is kept, and the flag stays in step. */
    @Test fun `the weather icon has three sizes and an older choice of large is kept`() = runTest {
        val repo = newRepository()
        repo.setWeatherIconSize(LauncherSettings.WEATHER_SIZE_XLARGE)
        assertThat(repo.settings.first().weatherIconSize).isEqualTo(LauncherSettings.WEATHER_SIZE_XLARGE)
        // The flag an older version reads: large there for anything above standard.
        assertThat(repo.exportRaw()["weather_icon_large"]).isEqualTo(true)
        assertThat(repo.exportRaw()["weather_icon_size"]).isEqualTo(LauncherSettings.WEATHER_SIZE_XLARGE)

        repo.setWeatherIconSize(LauncherSettings.WEATHER_SIZE_STANDARD)
        assertThat(repo.exportRaw()["weather_icon_large"]).isEqualTo(false)

        // An unknown size (a newer version's backup, a typo) reads as the standard one.
        repo.setWeatherIconSize("gigantic")
        assertThat(repo.settings.first().weatherIconSize).isEqualTo(LauncherSettings.WEATHER_SIZE_STANDARD)

        // A backup of an older version has only the flag.
        val older = newRepository()
        older.importRaw(mapOf("weather_icon_large" to true))
        assertThat(older.settings.first().weatherIconSize).isEqualTo(LauncherSettings.WEATHER_SIZE_LARGE)
        assertThat(LauncherSettings.WEATHER_SIZES).containsExactly(
            LauncherSettings.WEATHER_SIZE_STANDARD, LauncherSettings.WEATHER_SIZE_LARGE, LauncherSettings.WEATHER_SIZE_XLARGE,
        ).inOrder()
    }
}
