package org.arkikeskus.launcher.feature.settings

import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.model.LauncherSettings

/** Settings > Dock, with a preview of the dock at the chosen background. */
@Composable
internal fun DockRows(s: LauncherSettings, actions: SettingsActions) {
    val previewIcon = rememberLauncherIconBitmap()
    SettingRow("D1") {
        SwitchRow(stringResource(R.string.settings_dock_show), s.dockEnabled, actions::setDockEnabled)
    }
    SettingRow("D2") {
        StepperRow(stringResource(R.string.settings_dock_icons), s.dockColumns, 3, 7, actions::setDockColumns)
    }
    SettingRow("D3") {
        SwitchRow(stringResource(R.string.settings_show_labels), s.showDockLabels, actions::setShowDockLabels)
    }
    SettingRow("D4") {
        DockBackgroundRow(s.dockBackgroundOpacity, actions::setDockBackgroundOpacity)
    }
    SettingRow("D5") {
        DockBottomSpacingRow(s.dockBottomSpacing, actions::setDockBottomSpacing)
    }
    DockPreview(opacity = s.dockBackgroundOpacity, icon = previewIcon)
}

/** The dock background darkness as a percent of black, in 5 % steps with a tick of haptic feedback
 *  on each and a chip back to the default — the same slider as the status bar's. */
@Composable
internal fun DockBackgroundRow(value: Float, onValueChange: (Float) -> Unit) {
    val resources = LocalResources.current
    ScaleSliderRow(
        label = stringResource(R.string.settings_dock_bg),
        value = value,
        onValueChange = onValueChange,
        valueRange = 0f..1f,
        valueText = { resources.getString(R.string.settings_percent, scalePercent(it)) },
        default = LauncherSettings.DEFAULT_DOCK_BACKGROUND,
    )
}

/** Room under the dock (issue #24): 0 to 48 dp in 4 dp steps felt as ticks, read in dp beside the label, with a
 *  chip back to none. On top of the navigation-bar inset, so the dock lifts to where the thumb reaches. */
@Composable
internal fun DockBottomSpacingRow(value: Int, onValueChange: (Int) -> Unit) {
    val resources = LocalResources.current
    ScaleSliderRow(
        label = stringResource(R.string.settings_dock_bottom_space),
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        valueRange = 0f..LauncherSettings.MAX_DOCK_BOTTOM_SPACING.toFloat(),
        valueText = { resources.getString(R.string.settings_dp, it.roundToInt()) },
        step = DOCK_BOTTOM_SPACING_STEP,
        default = 0f,
    )
}

/** The dock-spacing slider's step in dp. */
private const val DOCK_BOTTOM_SPACING_STEP = 4f

/** Live preview of the dock at the chosen background opacity, over a neutral backdrop so the dark
 *  dock scrim stays visible on both light and dark themes. */
@Composable
private fun DockPreview(opacity: Float, icon: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(Color(0xFFB9C3D4), RoundedCornerShape(22.dp))
            .padding(8.dp),
    ) {
        Surface(
            color = Color.Black.copy(alpha = opacity),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                repeat(4) {
                    if (icon != null) {
                        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
                    } else {
                        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.6f), CircleShape))
                    }
                }
            }
        }
    }
}
