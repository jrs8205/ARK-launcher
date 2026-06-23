package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.arkikeskus.launcher.designsystem.component.BatteryBar
import org.arkikeskus.launcher.designsystem.component.SignalBars
import org.arkikeskus.launcher.designsystem.theme.LocalLauncherColors
import org.arkikeskus.launcher.model.SystemFlags
import org.arkikeskus.launcher.model.WifiBand
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LegibilityShadow = Shadow(color = Color(0x99000000), offset = Offset(0f, 1f), blurRadius = 4f)

/**
 * Full-width two-row status bar (matches the design): time + active system icons + battery% on
 * row 1; short date + Wi-Fi (bars + band), mobile (bars + generation) and battery glyph on row 2.
 * Left and right clusters are split with a centre gap that clears the camera punch-hole.
 */
@Composable
fun StatusBlock(
    modifier: Modifier = Modifier,
    viewModel: StatusBlockViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatusBlockContent(state = state, modifier = modifier)
}

@Composable
private fun StatusBlockContent(
    state: StatusBlockUiState,
    modifier: Modifier = Modifier,
) {
    val launcherColors = LocalLauncherColors.current
    val finnish = remember { Locale("fi", "FI") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("H.mm", finnish) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d.M.", finnish) }

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(10_000L)
        }
    }

    val batteryColor = launcherColors.batteryColor(state.battery.percent)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Left: time + date
        Column {
            Text(
                text = now.format(timeFormatter),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = now.format(dateFormatter),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 12.sp,
            )
        }

        // Centre gap that clears the camera hole.
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))

        // Right: indicators (two rows)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FlagIcons(state.flags)
                Text(
                    text = "${state.battery.percent} %",
                    color = batteryColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.wifi.connected) {
                    SignalCluster(
                        level = state.wifi.level,
                        color = launcherColors.signalColor(state.wifi.level),
                        label = bandLabel(state.wifi.band),
                    )
                }
                if (state.mobile.active && !state.flags.airplane) {
                    SignalCluster(
                        level = state.mobile.level,
                        color = launcherColors.signalColor(state.mobile.level),
                        label = state.mobile.generation,
                    )
                }
                BatteryBar(percent = state.battery.percent, color = batteryColor)
            }
        }
    }
}

@Composable
private fun SignalCluster(level: Int, color: Color, label: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SignalBars(level = level, activeColor = color)
        if (label != null) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FlagIcons(flags: SystemFlags) {
    val tint = Color.White
    val m = Modifier.size(14.dp)
    if (flags.airplane) Icon(Icons.Filled.AirplanemodeActive, null, m, tint)
    if (flags.silent) Icon(Icons.Filled.NotificationsOff, null, m, tint)
    if (flags.vibrate) Icon(Icons.Filled.Vibration, null, m, tint)
    if (flags.dnd) Icon(Icons.Filled.DoNotDisturbOn, null, m, tint)
    if (flags.location) Icon(Icons.Filled.LocationOn, null, m, tint)
    if (flags.dataSaver) Icon(Icons.Filled.DataSaverOn, null, m, tint)
    if (flags.vpn) Icon(Icons.Filled.VpnLock, null, m, tint)
    if (flags.nfc) Icon(Icons.Filled.Nfc, null, m, tint)
    if (flags.bluetooth) Icon(Icons.Filled.Bluetooth, null, m, tint)
    if (flags.alarm) Icon(Icons.Filled.Alarm, null, m, tint)
}

@Composable
private fun Text(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    androidx.compose.material3.Text(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            shadow = LegibilityShadow,
        ),
    )
}

private fun bandLabel(band: WifiBand): String? = when (band) {
    WifiBand.GHZ_2_4 -> "2.4G"
    WifiBand.GHZ_5 -> "5G"
    WifiBand.GHZ_6 -> "6G"
    WifiBand.UNKNOWN -> null
}
