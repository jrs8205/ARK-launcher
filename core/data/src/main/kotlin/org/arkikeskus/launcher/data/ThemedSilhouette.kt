package org.arkikeskus.launcher.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/** How far a legacy (non-adaptive) icon is inset into the adaptive layer, per side, as a fraction. */
internal const val LEGACY_GLYPH_INSET = 0.28f

/** The pixel size of the layer the silhouette is drawn into (a 108 dp adaptive layer at 2x). */
private const val SILHOUETTE_SIZE = 216

/**
 * Recolours [pixels] (ARGB) into a one-colour silhouette: every pixel keeps its own alpha and takes
 * [tint]'s colour, the tint's own alpha aside. A Telegram request: with "Theme every icon" on, an
 * app without a Material You monochrome layer is drawn as the silhouette of its icon.
 */
internal fun silhouettePixels(pixels: IntArray, tint: Int): IntArray {
    val rgb = tint and 0x00FFFFFF
    return IntArray(pixels.size) { i ->
        val alpha = pixels[i] and 0xFF000000.toInt()
        if (alpha == 0) 0 else alpha or rgb
    }
}

/**
 * Where the glyph sits in the layer: an adaptive foreground is already laid out for the 108 dp layer
 * and its 72 dp safe zone, a legacy icon fills its bounds and would be cut by the mask unless inset.
 */
internal fun themedGlyphInset(adaptive: Boolean): Float = if (adaptive) 0f else LEGACY_GLYPH_INSET

/**
 * Draws [glyph] into a layer-sized bitmap (inset when it is not an [adaptive] foreground) and turns
 * it into a one-colour silhouette in [tint], as the foreground of a themed adaptive icon.
 */
internal fun silhouetteDrawable(glyph: Drawable, tint: Int, adaptive: Boolean, resources: Resources): Drawable {
    val size = SILHOUETTE_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val inset = (size * themedGlyphInset(adaptive)).roundToInt()
    glyph.mutate().setBounds(inset, inset, size - inset, size - inset)
    glyph.draw(Canvas(bitmap))
    val pixels = IntArray(size * size)
    bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
    bitmap.setPixels(silhouettePixels(pixels, tint), 0, size, 0, 0, size, size)
    return BitmapDrawable(resources, bitmap)
}
