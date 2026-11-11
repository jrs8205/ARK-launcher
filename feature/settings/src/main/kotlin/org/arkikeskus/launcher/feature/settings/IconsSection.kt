package org.arkikeskus.launcher.feature.settings

import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import org.arkikeskus.launcher.data.IconPackInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.launcherFontFamily
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** Settings > Icons: label and icon size, label colour, font, themed icons and the icon pack. */
@Composable
internal fun IconsRows(s: LauncherSettings, iconPacks: List<IconPackInfo>, actions: SettingsActions) {
    val percentText = percentReadout()
    var showIconPackPicker by rememberSaveable { mutableStateOf(false) }
    SettingRow("I1") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_label_size),
            value = s.appLabelTextScale,
            onValueChange = actions::setAppLabelTextScale,
            valueRange = 0.8f..1.6f,
            valueText = percentText,
        )
    }
    SettingRow("I2") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_icon_size),
            value = s.iconScale,
            onValueChange = actions::setIconScale,
            valueRange = 0.8f..1.3f,
            valueText = percentText,
        )
    }
    SettingRow("I3") {
        LabelColorRow(selected = s.appLabelColor, onPick = actions::setAppLabelColor)
    }
    SettingRow("I4") {
        FontRow(selected = s.fontFamily, onPick = actions::setFontFamily)
    }
    // Themed (monochrome) icons need the adaptive-icon monochrome API (Android 13+).
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        SettingRow("I5") {
            SwitchRow(
                stringResource(R.string.settings_themed_icons),
                s.useThemedIcons,
                actions::setUseThemedIcons,
            )
        }
        if (s.useThemedIcons) {
            SettingRow("I6") {
                SwitchRow(
                    stringResource(R.string.settings_theme_all_icons),
                    stringResource(R.string.settings_theme_all_icons_desc),
                    s.themeAllIcons,
                    actions::setThemeAllIcons,
                )
            }
        }
    }
    val iconPackName = iconPacks.firstOrNull { it.packageName == s.iconPackPackage }?.label
        ?: stringResource(R.string.settings_icon_pack_system)
    SettingRow("I7") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_icon_pack),
            description = iconPackName,
        ) { showIconPackPicker = true }
    }
    if (showIconPackPicker) {
        IconPackPicker(
            packs = iconPacks,
            selected = s.iconPackPackage,
            onPick = { pkg ->
                actions.setIconPack(pkg)
                showIconPackPicker = false
            },
            onDismiss = { showIconPackPicker = false },
        )
    }
}

/** Single-select icon-pack picker: a "System default" entry on top, then every installed pack. */
@Composable
private fun IconPackPicker(
    packs: List<IconPackInfo>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalExpressivePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHi,
        title = { Text(stringResource(R.string.settings_icon_pack), color = p.text) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick("") }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_icon_pack_system),
                        color = if (selected.isBlank()) Accent else p.text,
                        fontSize = 16.sp,
                    )
                }
                packs.forEach { pack ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(pack.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pack.label,
                            color = if (selected == pack.packageName) Accent else p.text,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close), color = Accent) }
        },
    )
}

/** Row of preset label-color swatches for the home-surface app labels (home/dock/folders). The
 *  selected swatch gets an accent ring; every swatch has a faint outline so white reads on the card. */
@Composable
private fun LabelColorRow(selected: Int, onPick: (Int) -> Unit) {
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
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                COLOR_SWATCHES.forEach { c -> ColorSwatch(argb = c, selected = c == selected, onClick = { onPick(c) }) }
            }
        }
    }
}

/** A test tag per font chip, by its settings key. */
internal fun fontChipTag(key: String): String = "font-$key"

/** The name of a font choice, in its own words. */
private fun fontNameRes(key: String): Int = when (key) {
    LauncherSettings.FONT_CONDENSED -> R.string.settings_font_condensed
    LauncherSettings.FONT_SERIF -> R.string.settings_font_serif
    LauncherSettings.FONT_MONOSPACE -> R.string.settings_font_monospace
    LauncherSettings.FONT_CASUAL -> R.string.settings_font_casual
    LauncherSettings.FONT_CURSIVE -> R.string.settings_font_cursive
    else -> R.string.settings_font_default
}

/** The launcher's font (a Telegram request after 0.9.9): six families Android ships, each chip set in its
 *  own face so the choice can be seen before it is made. Widgets keep their own fonts. */
@Composable
internal fun FontRow(selected: String, onPick: (String) -> Unit) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_font))
            Text(stringResource(R.string.settings_font_desc), color = p.dim, fontSize = 12.5.sp)
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                LauncherSettings.FONTS.forEach { key ->
                    FilterChip(
                        selected = key == selected,
                        onClick = { onPick(key) },
                        label = { Text(stringResource(fontNameRes(key)), fontFamily = launcherFontFamily(key)) },
                        modifier = Modifier.testTag(fontChipTag(key)),
                    )
                }
            }
        }
    }
}
