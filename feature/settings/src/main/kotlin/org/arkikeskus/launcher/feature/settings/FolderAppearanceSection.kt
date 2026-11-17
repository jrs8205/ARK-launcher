package org.arkikeskus.launcher.feature.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow

/**
 * Settings > Folders: every folder setting on one page, wherever the folders are. First what a folder's icon and
 * its open card are made of (names, columns), then what an open folder looks like, then the app drawer's own
 * folders; [onOpenFolders] opens their sub-screen.
 */
@Composable
internal fun FoldersRows(s: LauncherSettings, lists: SettingsLists, actions: SettingsActions, onOpenFolders: () -> Unit) {
    FolderNameAndColumnRows(s, actions)
    PageHeading(stringResource(R.string.settings_folder_appearance))
    FolderAppearanceRows(s, actions)
    PageHeading(stringResource(R.string.settings_drawer))
    DrawerFolderRows(s, lists, actions, onOpenFolders)
}

/** The names under the folder icons of the home screen and of the app drawer, and the columns of an open folder. */
@Composable
private fun FolderNameAndColumnRows(s: LauncherSettings, actions: SettingsActions) {
    SettingRow("H5") {
        SwitchRow(stringResource(R.string.settings_home_folder_names), s.showHomeFolderNames, actions::setShowHomeFolderNames)
    }
    SettingRow("A5") {
        SwitchRow(stringResource(R.string.settings_drawer_folder_names), s.showDrawerFolderNames, actions::setShowDrawerFolderNames)
    }
    SettingRow("H6") {
        StepperRow(
            stringResource(R.string.settings_folder_columns), s.folderColumns,
            SettingsRepository.MIN_FOLDER_COLUMNS, SettingsRepository.MAX_FOLDER_COLUMNS, actions::setFolderColumns,
        )
    }
}

/** What an open folder looks like. */
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

/** The app drawer's own folders: the list of them, a new one, and their order among themselves and the apps. */
@Composable
private fun DrawerFolderRows(s: LauncherSettings, lists: SettingsLists, actions: SettingsActions, onOpenFolders: () -> Unit) {
    val drawerFolders = lists.drawerFolders
    val context = LocalContext.current
    SettingRow("A18") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_drawer_folders),
            description = pluralStringResource(
                R.plurals.settings_drawer_folders_count, drawerFolders.orEmpty().size, drawerFolders.orEmpty().size,
            ),
        ) { onOpenFolders() }
    }
    val newFolderName = stringResource(R.string.drawer_folder_default)
    val folderCreatedMsg = stringResource(R.string.settings_drawer_folder_created)
    SettingRow("A19") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_new_drawer_folder),
            description = stringResource(R.string.settings_new_drawer_folder_desc),
            trailingIcon = LauncherIcons.Add,
        ) {
            actions.createDrawerFolder(newFolderName)
            Toast.makeText(context, folderCreatedMsg, Toast.LENGTH_SHORT).show()
        }
    }
    SettingRow("A20") {
        SwitchRow(
            stringResource(R.string.settings_sort_drawer_folders),
            stringResource(R.string.settings_sort_drawer_folders_desc),
            s.sortDrawerFolders,
            actions::setSortDrawerFolders,
        )
    }
    SettingRow("A21") {
        SwitchRow(
            stringResource(R.string.settings_mix_drawer_folders),
            stringResource(R.string.settings_mix_drawer_folders_desc),
            s.mixDrawerFolders,
            actions::setMixDrawerFolders,
        )
    }
}
