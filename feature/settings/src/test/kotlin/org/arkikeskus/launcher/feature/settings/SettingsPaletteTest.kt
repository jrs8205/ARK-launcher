package org.arkikeskus.launcher.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.ui.READABLE_TEXT
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.expressive.toExpressive
import org.junit.Test

/**
 * The colours Settings writes with read on what is behind them (WCAG AA, 4.5:1 for text) in both themes and for
 * every kind of base colour: the text, the dim descriptions and the accent used as text, on the page and on a card.
 * A fill only has to stand 3:1 from a card, so the accent TEXT is the fill moved toward the text colour wherever the
 * fill itself would not read; fills keep the fill.
 */
class SettingsPaletteTest {
    private fun ratio(fg: Color, bg: Color) = contrastRatio(fg.luminance(), bg.luminance())

    // The default, the two hardest hues (a light fill and a dark one), a mid one, and no colour at all.
    private val seeds = listOf(
        "orange" to DefaultPaletteSeed,
        "yellow" to PaletteSeed.Hue(46f, 1f),
        "blue" to PaletteSeed.Hue(220f),
        "teal" to PaletteSeed.Hue(182f),
        "black" to PaletteSeed.Mono,
        "classic" to PaletteSeed.Classic,
    )

    private fun everyPalette(check: (String, org.arkikeskus.launcher.ui.expressive.ExpressivePalette) -> Unit) {
        for ((name, seed) in seeds) for (dark in listOf(true, false)) {
            check("$name ${if (dark) "dark" else "light"}", settingsPalette(basePalette(seed, dark)))
        }
    }

    @Test fun textDescriptionsAndAccentTextReadOnThePageAndOnACard() = everyPalette { at, p ->
        val inks = listOf("text" to p.text, "dim" to p.dim, "accent" to p.accent)
        val grounds = listOf("the page" to p.bg, "a card" to p.surfaceHi)
        for ((ink, colour) in inks) for ((ground, behind) in grounds) {
            assertWithMessage("$at: $ink on $ground").that(ratio(colour, behind)).isAtLeast(READABLE_TEXT)
        }
    }

    @Test fun whatIsDrawnOnAFillReadsAndTheFillStandsOutFromACard() = everyPalette { at, p ->
        assertWithMessage("$at: on the fill").that(ratio(p.onFill, p.fill)).isAtLeast(READABLE_TEXT)
        assertWithMessage("$at: the fill on a card").that(ratio(p.fill, p.surfaceHi)).isAtLeast(3f)
        assertWithMessage("$at: on a container").that(ratio(p.onContainer, p.container)).isAtLeast(READABLE_TEXT)
        assertWithMessage("$at: the off thumb on a card").that(ratio(p.thumbOff, p.surfaceHi)).isAtLeast(3f)
    }

    @Test fun settingsShowsTheBasePaletteItIsGiven() {
        for (dark in listOf(true, false)) {
            val base = basePalette(PaletteSeed.Hue(182f), dark)
            assertThat(settingsPalette(base)).isEqualTo(base.toExpressive())
        }
        assertThat(settingsPalette(basePalette(PaletteSeed.Hue(182f), true)))
            .isNotEqualTo(settingsPalette(basePalette(DefaultPaletteSeed, true)))
    }

    @Test fun theAccentTextIsTheFillWhereTheFillReadsAndMovedWhereItDoesNot() {
        // Orange on dark reads as it is; on light the fill is 3.7:1 on a card, so the text is deeper.
        val dark = settingsPalette(basePalette(DefaultPaletteSeed, dark = true))
        assertThat(dark.accent).isEqualTo(dark.fill)
        val light = settingsPalette(basePalette(DefaultPaletteSeed, dark = false))
        assertThat(ratio(light.fill, light.surfaceHi)).isLessThan(READABLE_TEXT)
        assertThat(light.accent).isNotEqualTo(light.fill)
        // Still an orange, only deeper: red leads, blue trails.
        assertThat(light.accent.red).isGreaterThan(light.accent.green)
        assertThat(light.accent.green).isGreaterThan(light.accent.blue)
        assertThat(light.accent.luminance()).isLessThan(light.fill.luminance())
    }
}
