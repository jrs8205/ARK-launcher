package org.arkikeskus.launcher.feature.settings

import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import org.arkikeskus.launcher.ui.component.DrawerWallpaperPicture
import org.arkikeskus.launcher.data.DrawerWallpaper
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.drawerSurface
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** Settings > App drawer; [onOpenHiddenApps] and [onOpenFolders] open the two sub-screens. */
@Composable
internal fun DrawerRows(
    s: LauncherSettings,
    lists: SettingsLists,
    actions: SettingsActions,
    blurAvailability: BlurAvailability,
    onOpenHiddenApps: () -> Unit,
    onOpenFolders: () -> Unit,
) {
    val drawerWallpaper = lists.drawerWallpaper
    val hiddenKeys = lists.hiddenKeys
    val drawerFolders = lists.drawerFolders
    val context = LocalContext.current
    SettingRow("A1") {
        StepperRow(stringResource(R.string.settings_columns), s.drawerColumns, 3, 7, actions::setDrawerColumns)
    }
    SettingRow("A2") {
        SwitchRow(stringResource(R.string.settings_show_labels), s.showDrawerLabels, actions::setShowDrawerLabels)
    }
    SettingRow("A3") {
        SwitchRow(stringResource(R.string.settings_two_line_labels), s.twoLineDrawerLabels, actions::setTwoLineDrawerLabels)
    }
    SettingRow("A4") {
        DrawerLabelColorRow(selected = s.drawerLabelColor, onPick = actions::setDrawerLabelColor)
    }
    SettingRow("A5") {
        SwitchRow(stringResource(R.string.settings_show_folder_names), s.showDrawerFolderNames, actions::setShowDrawerFolderNames)
    }
    SettingRow("A6") {
        SwitchRow(stringResource(R.string.settings_drawer_search), s.showDrawerSearch, actions::setShowDrawerSearch)
    }
    ContactsSearchRow(s, actions)
    SettingRow("A7") {
        SwitchRow(stringResource(R.string.settings_frequent_apps), s.showFrequentApps, actions::setShowFrequentApps)
    }
    SettingRow("A8") {
        SwitchRow(
            stringResource(R.string.settings_hide_home_apps),
            stringResource(R.string.settings_hide_home_apps_desc),
            s.hideHomeAppsFromDrawer,
            actions::setHideHomeAppsFromDrawer,
        )
    }
    SettingRow("A9") {
        SwitchRow(stringResource(R.string.settings_drawer_open_top), s.drawerOpensAtTop, actions::setDrawerOpensAtTop)
    }
    DrawerBackdropRows(
        settings = s,
        wallpaper = drawerWallpaper,
        blurAvailability = blurAvailability,
        actions = DrawerBackdropActions(
            onColor = actions::setDrawerBackgroundColor,
            onColorOpacity = actions::setDrawerBackgroundOpacity,
            onPickPicture = actions::importDrawerWallpaper,
            onClearPicture = actions::clearDrawerWallpaper,
            onPictureDim = actions::setDrawerPictureDim,
            onBlur = actions::setBlurBehindDrawer,
            onBlurStrength = actions::setDrawerBlurStrength,
        ),
    )
    SettingRow("A16") {
        DrawerIconSizeRow(s.drawerIconScale, actions::setDrawerIconScale)
    }
    SettingRow("A17") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_hidden_apps),
            description = pluralStringResource(R.plurals.settings_hidden_apps_desc, hiddenKeys.size, hiddenKeys.size),
        ) { onOpenHiddenApps() }
    }
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
            android.widget.Toast.makeText(context, folderCreatedMsg, android.widget.Toast.LENGTH_SHORT).show()
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

/** Whether the drawer's search also finds contacts (asks for the contacts permission). */
@Composable
internal fun ContactsSearchRow(s: LauncherSettings, actions: SettingsActions) {
    SettingRow("Q1") {
        ContactsSearchToggle(
            enabled = s.searchContacts,
            onSetEnabled = actions::setSearchContacts,
        )
    }
}

@Composable
private fun ContactsSearchToggle(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onSetEnabled(granted) // grant → on; deny → stays off
    }
    SwitchRow(stringResource(R.string.settings_search_contacts), enabled) { wantOn ->
        if (!wantOn) {
            onSetEnabled(false)
        } else {
            val has = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
            if (has) onSetEnabled(true) else launcher.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }
}

/** The app drawer's background opacity (issue #21): the dock row's twin, solid by default. */
/** The app drawer's own icon size (80–130 %, a Telegram request): until the user moves it, it shows and follows
 *  the home icon size (see LauncherSettings.drawerIconScale). */
@Composable
internal fun DrawerIconSizeRow(value: Float, onValueChange: (Float) -> Unit) {
    val resources = LocalResources.current
    ScaleSliderRow(
        label = stringResource(R.string.settings_drawer_icon_size),
        value = value,
        onValueChange = onValueChange,
        valueRange = 0.8f..1.3f,
        valueText = { resources.getString(R.string.settings_percent, scalePercent(it)) },
    )
}

/** The opacity of the drawer's colour over the wallpaper (issue #21); shown only while a colour is in effect. */
@Composable
internal fun DrawerBackgroundRow(value: Float, onValueChange: (Float) -> Unit) {
    val resources = LocalResources.current
    ScaleSliderRow(
        label = stringResource(R.string.settings_drawer_bg),
        value = value,
        onValueChange = onValueChange,
        valueRange = 0f..1f,
        valueText = { resources.getString(R.string.settings_percent, scalePercent(it)) },
        default = LauncherSettings.DEFAULT_DRAWER_BACKGROUND,
    )
}

/** The wallpaper-blur switch, greyed with the reason where this device cannot blur behind its windows. With the blur
 *  on behind a solid drawer ([drawerOpaque], the opacity slider at 100 %) nothing of it can show, so the description
 *  then says to lower the opacity instead (the owner saw a black drawer and looked for the blur). */
@Composable
internal fun BlurBehindDrawerRow(
    availability: BlurAvailability,
    checked: Boolean,
    drawerOpaque: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchRow(
        label = stringResource(R.string.settings_blur_behind_drawer),
        description = stringResource(
            when {
                availability == BlurAvailability.NEEDS_ANDROID_12 -> R.string.settings_blur_behind_drawer_needs_12
                availability == BlurAvailability.DEVICE_CANNOT -> R.string.settings_blur_behind_drawer_unavailable
                availability == BlurAvailability.PAUSED -> R.string.settings_blur_behind_drawer_paused
                checked && drawerOpaque -> R.string.settings_blur_behind_drawer_opaque
                else -> R.string.settings_blur_behind_drawer_desc
            },
        ),
        checked = checked,
        onCheckedChange = onCheckedChange,
        // A pause is the system's momentary state, not the device's: the setting stays the user's to change.
        enabled = availability == BlurAvailability.AVAILABLE || availability == BlurAvailability.PAUSED,
    )
}

/** How strong the wallpaper blur behind the open drawer is, 10–200 % of the 0.9.9 blur (a Telegram request). */
@Composable
internal fun DrawerBlurStrengthRow(value: Float, onValueChange: (Float) -> Unit) {
    val resources = LocalResources.current
    ScaleSliderRow(
        label = stringResource(R.string.settings_blur_strength),
        value = value,
        onValueChange = onValueChange,
        valueRange = SettingsRepository.MIN_DRAWER_BLUR_STRENGTH..SettingsRepository.MAX_DRAWER_BLUR_STRENGTH,
        valueText = { resources.getString(R.string.settings_percent, scalePercent(it)) },
    )
}

/** How much the user's own picture behind the drawer is darkened for legibility, 0–100 % black; none out of the box. */
@Composable
internal fun DrawerPictureDimRow(value: Float, onValueChange: (Float) -> Unit) {
    val resources = LocalResources.current
    ScaleSliderRow(
        label = stringResource(R.string.settings_drawer_picture_dim),
        value = value,
        onValueChange = onValueChange,
        valueRange = 0f..1f,
        valueText = { resources.getString(R.string.settings_percent, scalePercent(it)) },
        default = LauncherSettings.DEFAULT_DRAWER_PICTURE_DIM,
    )
}

/** The callbacks of [DrawerBackdropRows], one per setting they change. */
internal class DrawerBackdropActions(
    val onColor: (Int?) -> Unit = {},
    val onColorOpacity: (Float) -> Unit = {},
    val onPickPicture: (Uri) -> Unit = {},
    val onClearPicture: () -> Unit = {},
    val onPictureDim: (Float) -> Unit = {},
    val onBlur: (Boolean) -> Unit = {},
    val onBlurStrength: (Float) -> Unit = {},
)

/**
 * What the app drawer paints over what is behind it (the owner's 1.0.0 notes), top to bottom as the layers stack: the
 * colour strip ("Transparent" first, then the theme and the swatches) and the colour's opacity while a colour is in
 * effect; the user's own picture, which turns the colour off (the strip locks and says why) and takes a dim of its own
 * instead; then the wallpaper blur, whose "the drawer is solid" hint follows what the drawer really paints
 * ([drawerSurface]).
 */
@Composable
internal fun DrawerBackdropRows(
    settings: LauncherSettings,
    wallpaper: DrawerWallpaper?,
    blurAvailability: BlurAvailability,
    actions: DrawerBackdropActions,
) {
    val hasPicture = wallpaper != null
    val surface = drawerSurface(settings.drawerBackgroundColor, settings.drawerBackgroundOpacity, hasPicture, settings.drawerPictureDim)
    SettingRow("A10") {
        DrawerColorRow(selected = settings.drawerBackgroundColor, locked = hasPicture, onPick = actions.onColor)
    }
    if (!hasPicture && settings.drawerBackgroundColor != LauncherSettings.DRAWER_COLOR_TRANSPARENT) {
        SettingRow("A11") { DrawerBackgroundRow(settings.drawerBackgroundOpacity, actions.onColorOpacity) }
    }
    SettingRow("A12") {
        DrawerWallpaperRow(wallpaper = wallpaper, onPick = actions.onPickPicture, onClear = actions.onClearPicture)
    }
    if (hasPicture) SettingRow("A13") { DrawerPictureDimRow(settings.drawerPictureDim, actions.onPictureDim) }
    // Greyed with the reason where the system cannot blur behind the window (Android 11, a Fairphone 6 report),
    // instead of a switch that silently does nothing -- except with the user's own picture, which is blurred inside
    // the window on any Android 12 (forOwnPicture).
    val blur = if (hasPicture) blurAvailability.forOwnPicture() else blurAvailability
    SettingRow("A14") {
        BlurBehindDrawerRow(
            availability = blur,
            checked = settings.blurBehindDrawer,
            drawerOpaque = surface.opacity >= 1f,
            onCheckedChange = actions.onBlur,
        )
    }
    if (settings.blurBehindDrawer && blur == BlurAvailability.AVAILABLE) {
        SettingRow("A15") { DrawerBlurStrengthRow(settings.drawerBlurStrength, actions.onBlurStrength) }
    }
}

/** The test tags of the drawer wallpaper row's preview and buttons. */
internal const val DRAWER_WALLPAPER_PREVIEW_TAG = "drawer-wallpaper-preview"
internal const val DRAWER_WALLPAPER_PICK_TAG = "drawer-wallpaper-pick"
internal const val DRAWER_WALLPAPER_REMOVE_TAG = "drawer-wallpaper-remove"

/** The user's own picture behind the app drawer (a Telegram request after 0.9.9): a preview of the one set,
 *  a button to pick one from the gallery (the system photo picker, no permission), one to remove it. The
 *  picture stays on this phone: it is not in a backup. */
@Composable
internal fun DrawerWallpaperRow(wallpaper: DrawerWallpaper?, onPick: (Uri) -> Unit, onClear: () -> Unit) {
    val p = LocalExpressivePalette.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPick(uri)
    }
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_drawer_wallpaper))
            Text(stringResource(R.string.settings_drawer_wallpaper_desc), color = p.dim, fontSize = 12.5.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wallpaper != null) {
                    DrawerWallpaperPicture(
                        file = wallpaper.file,
                        blur = 0.dp,
                        contentDescription = stringResource(R.string.settings_drawer_wallpaper),
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).testTag(DRAWER_WALLPAPER_PREVIEW_TAG),
                    )
                } else {
                    Text(stringResource(R.string.settings_drawer_wallpaper_none), color = p.dim, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                    modifier = Modifier.testTag(DRAWER_WALLPAPER_PICK_TAG),
                ) {
                    Text(stringResource(R.string.settings_drawer_wallpaper_pick), color = p.accent)
                }
                if (wallpaper != null) {
                    TextButton(onClick = onClear, modifier = Modifier.testTag(DRAWER_WALLPAPER_REMOVE_TAG)) {
                        Text(stringResource(R.string.settings_drawer_wallpaper_remove), color = p.accent)
                    }
                }
            }
        }
    }
}

/** The app drawer's background colour (a Telegram request after 0.9.9): "Transparent" for no colour layer at all, the
 *  theme's own surface, or one of the label swatches; the drawer's text turns black or white to match by itself. While
 *  the user's own picture is set ([locked]) the colour is off: the strip keeps showing the choice but takes no taps and
 *  says why, so the colour is back as it was once the picture goes. */
@Composable
internal fun DrawerColorRow(selected: Int?, locked: Boolean = false, onPick: (Int?) -> Unit) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_drawer_color))
            if (locked) {
                Text(stringResource(R.string.settings_drawer_color_locked), color = p.dim, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = selected == LauncherSettings.DRAWER_COLOR_TRANSPARENT,
                    enabled = !locked,
                    onClick = { onPick(LauncherSettings.DRAWER_COLOR_TRANSPARENT) },
                    label = { Text(stringResource(R.string.settings_color_transparent)) },
                )
                FilterChip(
                    selected = selected == null,
                    enabled = !locked,
                    onClick = { onPick(null) },
                    label = { Text(stringResource(R.string.settings_color_theme)) },
                )
                COLOR_SWATCHES.forEach { c ->
                    ColorSwatch(argb = c, selected = c == selected, enabled = !locked, onClick = { onPick(c) })
                }
            }
        }
    }
}

/** The drawer's label colour row (the owner's 1.0.1 ask: the choice home's labels have): an "Automatic" chip for the
 *  black or white the drawer picks by its own look, then the label swatches. [selected] null = automatic. */
@Composable
internal fun DrawerLabelColorRow(selected: Int?, onPick: (Int?) -> Unit) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_label_color))
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = selected == null,
                    onClick = { onPick(null) },
                    label = { Text(stringResource(R.string.settings_color_automatic)) },
                )
                COLOR_SWATCHES.forEach { c -> ColorSwatch(argb = c, selected = c == selected, onClick = { onPick(c) }) }
            }
        }
    }
}
