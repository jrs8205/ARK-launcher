package org.arkikeskus.launcher.feature.settings

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
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** The clock and weather widget's own look: its background and its text size. */
@Composable
internal fun ClockWidgetLookRows(s: LauncherSettings, actions: SettingsActions) {
    val percentText = percentReadout()
    SettingRow("C1") {
        CardColorRow(
            title = stringResource(R.string.settings_clock_widget_color),
            selected = s.smartspaceBackgroundColor,
            onPick = actions::setSmartspaceBackgroundColor,
        )
    }
    SettingRow("C2") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_clock_widget_opacity),
            value = s.smartspaceBackgroundOpacity,
            onValueChange = actions::setSmartspaceBackgroundOpacity,
            valueRange = 0f..1f,
            valueText = percentText,
            default = LauncherSettings.DEFAULT_SMARTSPACE_BACKGROUND,
        )
    }
    SettingRow("C3") {
        ScaleSliderRow(
            label = stringResource(R.string.settings_clock_widget_text_size),
            value = s.smartspaceTextScale,
            onValueChange = actions::setSmartspaceTextScale,
            valueRange = SettingsRepository.MIN_SMARTSPACE_TEXT_SCALE..SettingsRepository.MAX_SMARTSPACE_TEXT_SCALE,
            valueText = percentText,
        )
    }
}

/** The weather icon's style and size; nothing while the weather is off. */
@Composable
internal fun WeatherIconRows(s: LauncherSettings, actions: SettingsActions) {
    if (s.showWeather) {
        SettingRow("C4") {
            WeatherIconRow(selected = s.weatherIconStyle, onPick = actions::setWeatherIconStyle)
        }
        SettingRow("C5") {
            WeatherIconSizeRow(large = s.weatherIconLarge, onPick = actions::setWeatherIconLarge)
        }
    }
}

/** How the clock/weather widget shows the weather's icon: the emoji, an icon drawn in colour, or the drawn icon
 *  moving for a moment whenever the home screen comes back. Its size is the row below ([WeatherIconSizeRow]). */
@Composable
internal fun WeatherIconRow(selected: String, onPick: (String) -> Unit) {
    val p = LocalExpressivePalette.current
    val styles = listOf(
        LauncherSettings.WEATHER_ICON_EMOJI to R.string.settings_weather_icon_standard,
        LauncherSettings.WEATHER_ICON_COLOR to R.string.settings_weather_icon_color,
        LauncherSettings.WEATHER_ICON_ANIMATED to R.string.settings_weather_icon_animated,
    )
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_weather_icon))
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                styles.forEach { (key, label) ->
                    FilterChip(selected = selected == key, onClick = { onPick(key) }, label = { Text(stringResource(label)) })
                }
            }
        }
    }
}

/** The weather icon's size, for every style: standard or large (the owner's note: a moving icon that is large too). */
@Composable
internal fun WeatherIconSizeRow(large: Boolean, onPick: (Boolean) -> Unit) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_weather_icon_size))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !large,
                    onClick = { onPick(false) },
                    label = { Text(stringResource(R.string.settings_weather_icon_standard)) },
                )
                FilterChip(
                    selected = large,
                    onClick = { onPick(true) },
                    label = { Text(stringResource(R.string.settings_weather_icon_large)) },
                )
            }
        }
    }
}
