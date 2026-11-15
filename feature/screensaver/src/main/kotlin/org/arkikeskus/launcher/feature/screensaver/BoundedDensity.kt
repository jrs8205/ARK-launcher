package org.arkikeskus.launcher.feature.screensaver

import android.util.DisplayMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/**
 * Draws [content] at a density and font scale bounded by the screensaver's own limits (see AppScale.kt),
 * so every label and readout stays on one line whatever the system settings are.
 */
@Composable
internal fun ProvideBoundedDensity(content: @Composable () -> Unit) {
    val systemDensity = LocalDensity.current
    val configuration = LocalConfiguration.current
    val defaultDensity = knownDefaultDensityDpi(DisplayMetrics.DENSITY_DEVICE_STABLE, configuration.densityDpi)
        ?.let { it / DisplayMetrics.DENSITY_DEFAULT.toFloat() }
    // The smallest width, not the current one: the bound is a property of the
    // device, and turning the phone sideways must not let the bars grow.
    val boundedDensity = remember(systemDensity, configuration.smallestScreenWidthDp, defaultDensity) {
        val widthPx = (configuration.smallestScreenWidthDp * systemDensity.density).roundToInt()
        Density(
            density = clampedDensity(systemDensity.density, defaultDensity, widthPx),
            fontScale = clampedFontScale(systemDensity.fontScale),
        )
    }
    CompositionLocalProvider(LocalDensity provides boundedDensity, content = content)
}
