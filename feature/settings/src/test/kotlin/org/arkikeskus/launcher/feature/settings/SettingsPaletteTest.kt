package org.arkikeskus.launcher.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.ui.READABLE_TEXT
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.junit.Test

/**
 * The colours Settings writes with read on what is behind them (WCAG AA, 4.5:1 for text) in both themes: the text,
 * the dim descriptions and the accent used as text, on the page and on a card. The orange accent itself is 2.9:1 on
 * the light card, so the light theme's accent TEXT is the orange moved toward the text colour; fills keep the orange.
 */
class SettingsPaletteTest {
    private fun ratio(fg: Color, bg: Color) = contrastRatio(fg.luminance(), bg.luminance())

    @Test fun textDescriptionsAndAccentTextReadOnThePageAndOnACardInBothThemes() {
        for (dark in listOf(true, false)) {
            val p = settingsPalette(dark)
            val inks = listOf("text" to p.text, "dim" to p.dim, "accent" to p.accent)
            val grounds = listOf("the page" to p.bg, "a card" to p.surfaceHi)
            for ((ink, colour) in inks) for ((ground, behind) in grounds) {
                assertWithMessage("${if (dark) "dark" else "light"}: $ink on $ground")
                    .that(ratio(colour, behind)).isAtLeast(READABLE_TEXT)
            }
        }
    }

    @Test fun theDarkThemeKeepsTheAccentAsItIs() {
        assertThat(settingsPalette(dark = true)).isEqualTo(DarkExpressivePalette)
        assertThat(settingsPalette(dark = true).accent).isEqualTo(Accent)
    }

    @Test fun theLightThemeChangesNothingButTheAccentText() {
        val light = settingsPalette(dark = false)
        assertThat(light.accent).isNotEqualTo(Accent)
        assertThat(light.copy(accent = Accent)).isEqualTo(LightExpressivePalette)
    }

    @Test fun theLightAccentTextIsARustOrange() {
        // The value the helper arrives at, pinned so a change to either palette shows up here.
        assertThat("#%06X".format(settingsPalette(dark = false).accent.toArgb() and 0xFFFFFF)).isEqualTo("#9C533B")
    }

    @Test fun theOrangeItselfDoesNotReadAsTextOnTheLightTheme() {
        // Why the light accent text exists: if this ever passes 4.5, the special case can go.
        assertThat(ratio(Accent, LightExpressivePalette.surfaceHi)).isLessThan(READABLE_TEXT)
        assertThat(ratio(Accent, LightExpressivePalette.bg)).isLessThan(READABLE_TEXT)
    }
}
