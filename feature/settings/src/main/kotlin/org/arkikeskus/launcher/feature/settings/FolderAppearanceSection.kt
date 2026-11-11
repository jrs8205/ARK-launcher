package org.arkikeskus.launcher.feature.settings

import android.provider.Settings
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.LauncherSettings

/** Settings > Folder appearance: what an open folder looks like. */
@Composable
internal fun FolderAppearanceRows(s: LauncherSettings, actions: SettingsActions) {
    val percentText = percentReadout()
    SettingRow("F1") {
        SwitchRow(stringResource(R.string.settings_folder_app_names), s.folderShowLabels, actions::setFolderShowLabels)
    }
    SettingRow("F2") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_folder_icon_size),
            value = s.folderIconScale,
            onValueChange = actions::setFolderIconScale,
            valueRange = SettingsRepository.MIN_FOLDER_ICON_SCALE..SettingsRepository.MAX_FOLDER_ICON_SCALE,
            valueText = percentText,
        )
    }
    SettingRow("F3") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_folder_spacing),
            value = s.folderSpacing,
            onValueChange = actions::setFolderSpacing,
            valueRange = SettingsRepository.MIN_FOLDER_SPACING..SettingsRepository.MAX_FOLDER_SPACING,
            valueText = percentText,
        )
    }
    SettingRow("F4") {
        CardColorRow(
            title = stringResource(R.string.settings_folder_color),
            selected = s.folderBackgroundColor,
            onPick = actions::setFolderBackgroundColor,
        )
    }
    SettingRow("F5") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_folder_opacity),
            value = s.folderBackgroundOpacity,
            onValueChange = actions::setFolderBackgroundOpacity,
            valueRange = SettingsRepository.MIN_CARD_OPACITY..1f,
            valueText = percentText,
        )
    }
}
