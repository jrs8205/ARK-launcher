package org.arkikeskus.launcher.feature.settings

import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.arkikeskus.launcher.ui.readableOn

/**
 * The colours of Settings in the dark or the light theme: the shared palettes, except that on the light theme the
 * accent used as TEXT (row titles, dialog buttons, the chosen option) is the orange moved toward the text colour
 * until it reads on the page, the harder of the two surfaces: the orange itself is 2.9:1 on a light card. Filled
 * things (a switch's track, a slider, the ring of the chosen swatch) keep the orange. The dark theme's orange reads
 * as it is.
 */
internal fun settingsPalette(dark: Boolean): ExpressivePalette =
    if (dark) DarkExpressivePalette else LightSettingsPalette

private val LightSettingsPalette = LightExpressivePalette.copy(
    accent = readableOn(Accent, on = LightExpressivePalette.bg, toward = LightExpressivePalette.text),
)
