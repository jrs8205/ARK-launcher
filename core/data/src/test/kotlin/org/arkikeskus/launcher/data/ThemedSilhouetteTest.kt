package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A Telegram request: with "Theme every icon" on, an app without a Material You monochrome layer
 * gets a one-colour silhouette of its own icon. The silhouette keeps the icon's shape (its alpha)
 * and paints every covered pixel in the theme's glyph colour; a legacy (non-adaptive) icon is
 * inset into the adaptive layer so the mask does not cut it.
 */
class ThemedSilhouetteTest {

    @Test fun everyCoveredPixelTakesTheGlyphColourAndKeepsItsAlpha() {
        val red = 0xFFFF0000.toInt()
        val halfBlue = 0x800000FF.toInt()
        val clear = 0x00123456
        val tint = 0xFF336699.toInt()

        val out = silhouettePixels(intArrayOf(red, halfBlue, clear), tint)

        assertThat(out[0]).isEqualTo(0xFF336699.toInt())
        assertThat(out[1]).isEqualTo(0x80336699.toInt())
        assertThat(out[2]).isEqualTo(0)
    }

    @Test fun theTintsOwnAlphaIsIgnored() {
        val out = silhouettePixels(intArrayOf(0xFF000000.toInt()), tint = 0x10ABCDEF)
        assertThat(out[0]).isEqualTo(0xFFABCDEF.toInt())
    }

    @Test fun aLegacyIconIsInsetIntoTheLayerAndAnAdaptiveForegroundIsNot() {
        assertThat(themedGlyphInset(adaptive = true)).isEqualTo(0f)
        assertThat(themedGlyphInset(adaptive = false)).isWithin(1e-6f).of(LEGACY_GLYPH_INSET)
        assertThat(LEGACY_GLYPH_INSET).isAtLeast(0.2f)
        assertThat(LEGACY_GLYPH_INSET).isAtMost(0.35f)
    }
}
