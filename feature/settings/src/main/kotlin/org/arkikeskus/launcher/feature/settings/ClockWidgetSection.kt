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
import org.arkikeskus.launcher.ui.expressive.expressiveCardGap
import org.arkikeskus.launcher.ui.expressive.expressiveCardShadow
import org.arkikeskus.launcher.ui.expressive.expressiveCardShape

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
            WeatherIconSizeRow(size = s.weatherIconSize, onPick = actions::setWeatherIconSize)
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
        shape = expressiveCardShape(),
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().padding(bottom = expressiveCardGap()),
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

/** The weather icon's size, for every style: standard, large (the owner's note: a moving icon that is large too)
 *  or extra large (his word of 2026-11-19: larger still). */
@Composable
internal fun WeatherIconSizeRow(size: String, onPick: (String) -> Unit) {
    val sizes = listOf(
        LauncherSettings.WEATHER_SIZE_STANDARD to R.string.settings_weather_icon_standard,
        LauncherSettings.WEATHER_SIZE_LARGE to R.string.settings_weather_icon_large,
        LauncherSettings.WEATHER_SIZE_XLARGE to R.string.settings_weather_icon_xlarge,
    )
    val selected = size.takeIf { known -> sizes.any { it.first == known } } ?: LauncherSettings.WEATHER_SIZE_STANDARD
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = expressiveCardShape(),
        shadowElevation = expressiveCardShadow(),
        modifier = Modifier.fillMaxWidth().padding(bottom = expressiveCardGap()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            RowLabel(stringResource(R.string.settings_weather_icon_size))
            Spacer(Modifier.height(14.dp))
            // Three chips do not fit a narrow screen in every language: the row scrolls, as the style's does.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((size, label) in sizes) {
                    FilterChip(
                        selected = size == selected,
                        onClick = { onPick(size) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
    }
}
