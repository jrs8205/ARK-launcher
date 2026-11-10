package org.arkikeskus.launcher.model

/**
 * The glass layer's white tint over the wallpaper (alpha 0..1): stronger at the [top] of the screen than
 * at the [bottom]. The layer covers the whole screen, so the home screen's cards and the system bars'
 * glyphs both choose their colours against it.
 */
data class GlassTint(val top: Float, val bottom: Float) {
    /** The tint [fraction] of the way down the screen (0 = the top, 1 = the bottom): it fades evenly. */
    fun at(fraction: Float): Float = top + (bottom - top) * fraction.coerceIn(0f, 1f)
}

/** The glass layer's tint at the strength setting [opacity]. */
fun glassTint(opacity: Float): GlassTint {
    val o = opacity.coerceIn(0f, 1f)
    return GlassTint(top = (o * 1.35f).coerceAtMost(1f), bottom = o * 0.7f)
}
