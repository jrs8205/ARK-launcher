package org.arkikeskus.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.pow

// What is on screen, for every "dark or light text" decision (the drawer's text, the system bars' glyphs): the window
// composites its layers in sRGB (gamma) space, so a dim or a half-transparent surface is judged by the grey the screen
// really shows -- black at 32 % over white is sRGB 0.68, relative luminance 0.42, not the 0.68 a linear mix of the two
// luminances gives -- and black or white is chosen by the WCAG contrast ratio against that.

/** The sRGB grey level (0..1, gamma-encoded as the screen holds it) of a relative [luminance]: the sRGB transfer function. */
fun srgbGrey(luminance: Float): Float {
    val l = luminance.coerceIn(0f, 1f)
    return if (l <= 0.0031308f) l * 12.92f else 1.055f * l.toDouble().pow(1 / 2.4).toFloat() - 0.055f
}

/** The relative luminance of an sRGB [grey] level: the inverse of [srgbGrey], what [luminance] applies per channel. */
fun greyLuminance(grey: Float): Float {
    val g = grey.coerceIn(0f, 1f)
    return if (g <= 0.04045f) g / 12.92f else ((g + 0.055) / 1.055).pow(2.4).toFloat()
}

/** The relative luminance on screen of something of [luminance] under black at [alpha], composited in sRGB space. */
fun dimmedLuminance(luminance: Float, alpha: Float): Float =
    greyLuminance(srgbGrey(luminance) * (1f - alpha.coerceIn(0f, 1f)))

/** The relative luminance on screen of [layer] at [alpha] over something of [behindLuminance] (a grey of it),
 *  composited per channel in sRGB space as the window composites it. */
fun shownLuminance(layer: Color, alpha: Float, behindLuminance: Float): Float {
    val grey = srgbGrey(behindLuminance)
    return layer.copy(alpha = alpha.coerceIn(0f, 1f)).compositeOver(Color(grey, grey, grey)).luminance()
}

/** The WCAG contrast ratio between two relative luminances, 1 (equal) to 21 (black and white). */
fun contrastRatio(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

/** Whether black text or glyphs contrast at least as much as white against something of [luminance] on screen: from a
 *  relative luminance of 0.179 (L* 50, the perceptual mid grey) up. */
fun darkReadsBetter(luminance: Float): Boolean = contrastRatio(0f, luminance) >= contrastRatio(1f, luminance)
