package org.arkikeskus.launcher.designsystem.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** The Chameleon choice: the wallpaper's main colour as the palette's seed. */
class ChameleonSeedTest {
    @Test fun `a coloured wallpaper gives its hue at the presets' strength`() {
        assertThat((chameleonSeed(0xFF1F8EF1.toInt()) as PaletteSeed.Hue).hue).isWithin(2f).of(209f)
        assertThat((chameleonSeed(0xFF3A7D44.toInt()) as PaletteSeed.Hue).hue).isWithin(2f).of(129f)
        // A muted wallpaper still gives a vivid launcher: only the hue is taken.
        assertThat((chameleonSeed(0xFF8899AA.toInt()) as PaletteSeed.Hue).saturation).isEqualTo(0.96f)
    }

    @Test fun `a red wallpaper sits at the start of the circle, not at its end`() {
        assertThat((chameleonSeed(0xFFE01020.toInt()) as PaletteSeed.Hue).hue).isWithin(3f).of(355f)
        assertThat((chameleonSeed(0xFFE02010.toInt()) as PaletteSeed.Hue).hue).isWithin(3f).of(5f)
    }

    @Test fun `no colour, a grey, a nearly black and a nearly white wallpaper are monochrome`() {
        listOf(null, 0xFF808080.toInt(), 0xFF8A8886.toInt(), 0xFF050308.toInt(), 0xFFFDFCFE.toInt(), 0x00FF0000)
            .forEach { assertWithMessage("${it?.toUInt()?.toString(16)}").that(chameleonSeed(it)).isEqualTo(PaletteSeed.Mono) }
    }

    @Test fun `every seed the chameleon can give makes a palette`() {
        for (argb in listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF, 0xFF000000, 0xFFFFFFFF)) {
            val seed = chameleonSeed(argb.toInt())
            basePalette(seed, dark = true)
            basePalette(seed, dark = false)
        }
    }
}
