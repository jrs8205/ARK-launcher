package org.arkikeskus.launcher.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.arkikeskus.launcher.ui.darkReadsBetter

/**
 * The check on a chosen colour swatch: the black or the white that reads better on [swatch], always 4.5:1 or
 * more. A swatch is a colour the user is offered, not a themed surface, so no palette colour is sure to read
 * on it: the fill itself can be the swatch's colour.
 */
internal fun swatchMarkColor(swatch: Color): Color = if (darkReadsBetter(swatch.luminance())) Color.Black else Color.White
