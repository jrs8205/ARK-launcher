package org.arkikeskus.launcher.designsystem.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * What the screens need beyond the roles: the same colour in the other mode (the app drawer picks
 * its palette by the text its own background needs, not by the phone's mode), and the accent as a
 * text colour that reads wherever Settings puts it.
 */
class BasePaletteModesTest {
    @Test fun `a palette knows its seed and gives the same colour in the other mode`() {
        val dark = basePalette(PaletteSeed.Hue(182f), dark = true)
        assertThat(dark.seed).isEqualTo(PaletteSeed.Hue(182f))
        assertThat(dark.inMode(dark = false)).isEqualTo(basePalette(PaletteSeed.Hue(182f), dark = false))
        assertThat(dark.inMode(dark = true)).isSameInstanceAs(dark)
        val mono = basePalette(PaletteSeed.Mono, dark = false)
        assertThat(mono.inMode(dark = true)).isEqualTo(basePalette(PaletteSeed.Mono, dark = true))
    }

    @Test fun `the seed a palette keeps is the one it was really made from`() {
        assertThat(basePalette(PaletteSeed.Hue(375f), true).seed).isEqualTo(PaletteSeed.Hue(15f))
        assertThat(basePalette(PaletteSeed.Hue(46f, 3f), true).seed).isEqualTo(PaletteSeed.Hue(46f, 1f))
        assertThat(basePalette(PaletteSeed.Hue(Float.NaN), true).seed).isEqualTo(PaletteSeed.Mono)
    }

    @Test fun `the accent text reads on the page and on a card alike`() {
        val seeds = (0 until 360).map { PaletteSeed.Hue(it.toFloat()) } + PaletteSeed.Mono
        for (seed in seeds) for (dark in listOf(true, false)) {
            val p = basePalette(seed, dark)
            assertWithMessage("$seed dark=$dark page").that(contrast(p.accentText, p.background)).isAtLeast(4.5f)
            assertWithMessage("$seed dark=$dark card").that(contrast(p.accentText, p.card)).isAtLeast(4.5f)
        }
    }

    @Test fun `the accent text is the fill itself where the fill already reads`() {
        val yellow = basePalette(PaletteSeed.Hue(46f, 1f), dark = true)
        assertThat(yellow.accentText).isEqualTo(yellow.fill)
        val blue = basePalette(PaletteSeed.Hue(220f), dark = true)
        assertThat(blue.accentText).isNotEqualTo(blue.fill)
    }
}
