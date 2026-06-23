package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Launcher-specific dynamic colors that are NOT part of the Material 3 [androidx.compose.material3.ColorScheme]:
 * the dynamically colored battery indicator and the signal-quality bar colors from the design handoff.
 * Provided via [LocalLauncherColors].
 */
@Immutable
data class LauncherColors(
    val batteryHigh: Color,
    val batteryMedium: Color,
    val batteryLow: Color,
    /** Signal quality colors, index 0 (weakest) .. 4 (strongest). */
    val signal: List<Color>,
    val signalFaint: Color,
    val dockScrim: Color,
    val pageIndicatorActive: Color,
    val pageIndicatorInactive: Color,
) {
    fun batteryColor(percent: Int): Color = when {
        percent <= 20 -> batteryLow
        percent <= 45 -> batteryMedium
        else -> batteryHigh
    }

    fun signalColor(level: Int): Color = signal[level.coerceIn(0, 4)]
}

internal val LightLauncherColors = LauncherColors(
    batteryHigh = Color(0xFF86E0A6),
    batteryMedium = Color(0xFFFFC078),
    batteryLow = Color(0xFFFF9A92),
    signal = listOf(
        Color(0xFFFF9A92),
        Color(0xFFFF9A92),
        Color(0xFFFFC078),
        Color(0xFFB8E986),
        Color(0xFF86E0A6),
    ),
    signalFaint = Color(0x52FFFFFF),
    dockScrim = Color(0x2EFFFFFF),
    pageIndicatorActive = Color(0xFFFFFFFF),
    pageIndicatorInactive = Color(0x66FFFFFF),
)

internal val DarkLauncherColors = LauncherColors(
    batteryHigh = Color(0xFF8FD89E),
    batteryMedium = Color(0xFFFFB68A),
    batteryLow = Color(0xFFFFB4AB),
    signal = listOf(
        Color(0xFFFFB4AB),
        Color(0xFFFFB4AB),
        Color(0xFFFFB68A),
        Color(0xFFC8E6A0),
        Color(0xFF8FD89E),
    ),
    signalFaint = Color(0x29FFFFFF),
    dockScrim = Color(0x12FFFFFF),
    pageIndicatorActive = Color(0xFFFFFFFF),
    pageIndicatorInactive = Color(0x4DEAF0FF),
)

val LocalLauncherColors = staticCompositionLocalOf<LauncherColors> {
    error("LauncherColors not provided. Wrap content in LauncherTheme.")
}
