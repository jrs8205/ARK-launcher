package org.arkikeskus.launcher.feature.settings

import org.arkikeskus.launcher.designsystem.theme.BasePalette
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.toExpressive

/**
 * The colours of Settings: the base palette of the user's colour. The accent used as TEXT (row titles, dialog
 * buttons, the chosen option) is the palette's accent text, the fill moved toward the text colour where the fill
 * itself would not read on the page or on a card; filled things (a switch's track, a slider, the ring of the chosen
 * swatch) keep the fill.
 */
internal fun settingsPalette(base: BasePalette): ExpressivePalette = base.toExpressive()
