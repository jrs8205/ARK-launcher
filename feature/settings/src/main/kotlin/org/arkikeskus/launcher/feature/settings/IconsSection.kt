package org.arkikeskus.launcher.feature.settings

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
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
import org.arkikeskus.launcher.model.IconShape
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.iconShapePath
import org.arkikeskus.launcher.ui.launcherFontFamily
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.expressiveCardGap
import org.arkikeskus.launcher.ui.expressive.expressiveCardShadow
import org.arkikeskus.launcher.ui.expressive.expressiveCardShape

/** Settings > Icons: label and icon size, label colour, font, themed icons, the icon pack and the icon shape. */
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
    SettingRow("I8") {
        IconShapeRow(selected = s.iconShape, onPick = actions::setIconShape)
    }
}

/** A test tag per icon shape tile, by the shape's id. */
internal fun iconShapeTag(shape: IconShape): String = "icon-shape-${shape.id}"

/** The name of an icon shape, in its own words. */
private fun iconShapeNameRes(shape: IconShape): Int = when (shape) {
    IconShape.SYSTEM -> R.string.icon_shape_system
    IconShape.CIRCLE -> R.string.icon_shape_circle
    IconShape.SQUIRCLE -> R.string.icon_shape_squircle
    IconShape.ROUNDED_SQUARE -> R.string.icon_shape_rounded_square
    IconShape.SQUARE -> R.string.icon_shape_square
    IconShape.TEARDROP -> R.string.icon_shape_teardrop
    IconShape.HEXAGON -> R.string.icon_shape_hexagon
    IconShape.OCTAGON -> R.string.icon_shape_octagon
    IconShape.FLOWER -> R.string.icon_shape_flower
}

/**
 * The icon shape (issue #26): one tile per shape, each drawn as its own outline so the choice can be seen
 * before it is made. The system's tile shows the phone's own icon mask. One of them is always chosen.
 */
@Composable
internal fun IconShapeRow(selected: IconShape, onPick: (IconShape) -> Unit) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = expressiveCardShape(),
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().padding(bottom = expressiveCardGap()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_icon_shape))
            Text(stringResource(R.string.settings_icon_shape_desc), color = p.dim, fontSize = 12.5.sp)
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            ) {
                IconShape.entries.forEach { shape ->
                    IconShapeTile(shape, selected = shape == selected, onClick = { onPick(shape) })
                }
            }
        }
    }
}

@Composable
private fun IconShapeTile(shape: IconShape, selected: Boolean, onClick: () -> Unit) {
    val p = LocalExpressivePalette.current
    val ink = if (selected) p.accent else p.dim
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(iconShapeTag(shape))
            .padding(horizontal = 2.dp, vertical = 8.dp),
    ) {
        Canvas(Modifier.size(44.dp)) {
            drawPath(iconShapeOutline(shape, size.minDimension).asComposePath(), ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(iconShapeNameRes(shape)),
            color = if (selected) p.accent else p.text,
            fontSize = 11.5.sp,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** The outline a tile draws: the shape's own, or for the system's the phone's icon mask. */
private fun iconShapeOutline(shape: IconShape, sizePx: Float): android.graphics.Path =
    iconShapePath(shape, sizePx)
        ?: AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.BLACK), null)
            .apply { setBounds(0, 0, sizePx.toInt(), sizePx.toInt()) }.iconMask

/** Single-select icon-pack picker: a "System default" entry on top, then every installed pack. */
@Composable
internal fun IconPackPicker(
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
                        .heightIn(min = 48.dp)
                        .clickable { onPick("") }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_icon_pack_system),
                        color = if (selected.isBlank()) p.accent else p.text,
                        fontSize = 16.sp,
                    )
                }
                packs.forEach { pack ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onPick(pack.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pack.label,
                            color = if (selected == pack.packageName) p.accent else p.text,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close), color = p.accent) }
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
        shape = expressiveCardShape(),
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().padding(bottom = expressiveCardGap()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_label_color))
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
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
        shape = expressiveCardShape(),
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().padding(bottom = expressiveCardGap()),
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
