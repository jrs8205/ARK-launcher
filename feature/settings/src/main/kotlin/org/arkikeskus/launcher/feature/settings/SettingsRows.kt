package org.arkikeskus.launcher.feature.settings

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.ExpressiveCard
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** Single-select picker over string-valued settings (widget count style, people privacy). */
@Composable
internal fun ChoicePicker(
    @androidx.annotation.StringRes title: Int,
    options: List<Pair<String, Int>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalExpressivePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHi,
        title = { Text(stringResource(title), color = p.text) },
        text = {
            Column {
                options.forEach { (value, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(labelRes),
                            color = if (selected == value) Accent else p.text,
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

/** Single-select app picker (the left-edge swipe app, the weather app): a "None" entry on top, then every app. */
@Composable
internal fun SingleAppPicker(
    apps: List<AppItem>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalExpressivePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHi,
        title = { Text(stringResource(R.string.settings_left_edge_pick), color = p.text) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(null) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_left_edge_none), color = Accent, fontSize = 16.sp)
                }
                apps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app.key) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(appItem = app, labelColor = p.text, showLabel = false, iconSize = 32.dp)
                        Text(app.label, modifier = Modifier.padding(start = 12.dp), color = p.text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close), color = Accent) }
        },
    )
}

internal fun openLink(context: android.content.Context, url: String) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        // No browser (or the handler is disabled): say so and show the address instead of a dead tap.
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.settings_open_link_failed, url),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}

@Composable
internal fun RowLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalExpressivePalette.current.text) {
    Text(text, modifier = modifier, color = color, fontSize = 16.sp)
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
    SwitchRow(label, description = null, checked, onCheckedChange)

/** A setting switch; [enabled] false greys the label and the switch, the description then says why. */
@Composable
internal fun SwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val p = LocalExpressivePalette.current
    val labelColor = if (enabled) p.text else p.dim
    ExpressiveCard {
        if (description == null) {
            RowLabel(label, Modifier.weight(1f), color = labelColor)
        } else {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                RowLabel(label, color = labelColor)
                Text(description, color = p.dim, fontSize = 12.5.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = Color.White,
                checkedBorderColor = Accent,
                uncheckedTrackColor = p.trackOff,
                uncheckedThumbColor = p.thumbOff,
                uncheckedBorderColor = p.trackOff,
            ),
        )
    }
}

@Composable
internal fun StepperRow(label: String, value: Int, min: Int, max: Int, onValueChange: (Int) -> Unit) {
    val p = LocalExpressivePalette.current
    ExpressiveCard {
        RowLabel(label, Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepButton(LauncherIcons.Remove, stringResource(R.string.settings_step_decrease)) {
                if (value > min) onValueChange(value - 1)
            }
            Text(
                text = "$value",
                modifier = Modifier.widthIn(min = 26.dp),
                textAlign = TextAlign.Center,
                color = p.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            StepButton(LauncherIcons.Add, stringResource(R.string.settings_step_increase)) {
                if (value < max) onValueChange(value + 1)
            }
        }
    }
}

@Composable
private fun StepButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit) {
    val p = LocalExpressivePalette.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(p.btn)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The colours offered for app labels and the drawer's background: white, black, grey and six Material hues. */
internal val COLOR_SWATCHES: List<Int> = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFFEF5350,
    0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC,
).map { it.toInt() }

/** A test tag per swatch, by its colour. */
internal fun colorSwatchTag(argb: Int): String = "color-swatch-%06X".format(argb and 0xFFFFFF)

/** One round colour swatch; the chosen one wears the accent ring. Disabled ([enabled] false) it fades and takes no taps. */
@Composable
internal fun ColorSwatch(argb: Int, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalExpressivePalette.current
    val name = "#%06X".format(argb and 0xFFFFFF)
    val fade = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(argb).copy(alpha = fade))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = (if (selected) Accent else p.faint).copy(alpha = fade),
                shape = CircleShape,
            )
            .testTag(colorSwatchTag(argb))
            .semantics { contentDescription = name; this.selected = selected }
            .clickable(enabled = enabled) { onClick() },
    )
}

/** A card's background colour (the open folder's, the clock/weather widget's): a "Theme" chip for the card's own
 *  colour, then the swatches. [selected] null = the theme's. */
@Composable
internal fun CardColorRow(title: String, selected: Int?, onPick: (Int?) -> Unit) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(title)
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = selected == null,
                    onClick = { onPick(null) },
                    label = { Text(stringResource(R.string.settings_color_theme)) },
                )
                COLOR_SWATCHES.forEach { c -> ColorSwatch(argb = c, selected = c == selected, onClick = { onPick(c) }) }
            }
        }
    }
}

/** The launcher's own app icon, for use as the settings preview sample. */
@Composable
internal fun rememberLauncherIconBitmap(): ImageBitmap? {
    val context = LocalContext.current
    return remember {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val size = 144
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

/** A size multiplier's readout as a whole percent, so 100 is the default and the number is easy to come back to. */
@Composable
internal fun percentReadout(): (Float) -> String {
    val resources = LocalResources.current
    return { resources.getString(R.string.settings_percent, scalePercent(it)) }
}
