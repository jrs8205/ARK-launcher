package org.arkikeskus.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The luminance model behind every "dark or light text" decision (the drawer's text, the system bars' glyphs): what is
 * ON SCREEN -- layers composited in sRGB (gamma) space, as the window composites them -- and the WCAG contrast ratio
 * of black and white against it. A linear mix of relative luminances overstates a dimmed or half-transparent light
 * surface: black at 32 % over white is sRGB 0.68 = relative luminance 0.42, not 0.68.
 */
class ScreenLuminanceTest {
    @Test fun theSrgbGreyOfALuminanceRoundTrips() {
        for (l in listOf(0f, 0.001f, 0.0031308f, 0.01f, 0.18f, 0.42f, 0.5f, 1f)) {
            assertThat(greyLuminance(srgbGrey(l))).isWithin(1e-4f).of(l)
            val grey = srgbGrey(l)
            assertThat(Color(grey, grey, grey).luminance()).isWithin(5e-3f).of(l) // sRGB colours hold 8 bits per channel
        }
        assertThat(srgbGrey(0.5f)).isWithin(1e-3f).of(0.735f)
        assertThat(srgbGrey(0.214f)).isWithin(1e-2f).of(0.5f)
    }

    /** The shell's 32 % black dim over a white wallpaper leaves sRGB 0.68 on screen: relative luminance 0.42, not 0.68;
     *  the own bar's default 60 % scrim leaves sRGB 0.4 = 0.13. */
    @Test fun aBlackLayerDimsInSrgbSpace() {
        assertThat(dimmedLuminance(1f, 0.32f)).isWithin(0.01f).of(0.42f)
        assertThat(dimmedLuminance(1f, 0.6f)).isWithin(0.01f).of(0.13f)
        assertThat(dimmedLuminance(1f, 0f)).isWithin(1e-4f).of(1f)
        assertThat(dimmedLuminance(1f, 1f)).isEqualTo(0f)
        assertThat(dimmedLuminance(0f, 0.32f)).isEqualTo(0f)
        assertThat(dimmedLuminance(2f, -1f)).isWithin(1e-4f).of(1f) // clamped
    }

    /** A colour at an opacity over a grey of the given luminance, per channel in sRGB space: opaque, the colour's own
     *  luminance; transparent, what is behind; black at 40 % over the dimmed white wallpaper (0.42) is sRGB 0.41 = 0.14;
     *  a light theme's surface at 30 % over black is #4B4B4B = 0.07. */
    @Test fun aLayerShowsAsTheScreenComposesIt() {
        val orange = Color(0xFFFFA726)
        assertThat(shownLuminance(orange, 1f, 0f)).isWithin(1e-4f).of(orange.luminance())
        assertThat(shownLuminance(orange, 0f, 0.42f)).isWithin(5e-3f).of(0.42f)
        assertThat(shownLuminance(Color.Black, 0.4f, dimmedLuminance(1f, 0.32f))).isWithin(0.01f).of(0.14f)
        assertThat(shownLuminance(Color(0xFFFBFCFF), 0.3f, 0f)).isWithin(0.01f).of(0.07f)
        assertThat(shownLuminance(Color.White, 2f, 0f)).isWithin(1e-4f).of(1f) // clamped
    }

    /** A foreground that does not read on its background is pushed toward the text colour that does, just far enough:
     *  the accent #E2714A on a red card #F16865 (1.03:1) becomes a darker accent at 4.5:1 or better; one that already
     *  reads is left as it is; one that cannot reach the target short of the text colour becomes the text colour. */
    @Test fun aForegroundIsPushedTowardTheTextUntilItReads() {
        val accent = Color(0xFFE2714A)
        val redCard = Color(0xFFF16865)
        val pushed = readableOn(accent, redCard, Color.Black)
        assertThat(contrastRatio(pushed.luminance(), redCard.luminance())).isAtLeast(4.5f)
        assertThat(pushed.luminance()).isLessThan(accent.luminance())
        assertThat(pushed.red).isGreaterThan(pushed.blue) // still the accent's hue
        assertThat(readableOn(Color.Black, Color.White, Color.Black)).isEqualTo(Color.Black)
        assertThat(readableOn(Color.White, Color.White, Color.Black, target = 21f)).isEqualTo(Color.Black)
        assertThat(contrastRatio(readableOn(Color(0xFFE2714A), Color(0xFF963EA5), Color.White).luminance(), Color(0xFF963EA5).luminance())).isAtLeast(4.5f)
    }

    /** Black reads better than white from a relative luminance of 0.179 (L* 50, the perceptual mid grey) up; the WCAG
     *  ratio runs from 1 (equal) to 21 (black and white). Orange #FFA726 (0.49): black 10.8:1, white 1.9:1. */
    @Test fun blackReadsBetterFromMidGreyUp() {
        assertThat(contrastRatio(0f, 1f)).isWithin(1e-4f).of(21f)
        assertThat(contrastRatio(1f, 0f)).isWithin(1e-4f).of(21f)
        assertThat(contrastRatio(0.5f, 0.5f)).isEqualTo(1f)
        assertThat(contrastRatio(0f, 0.49f)).isWithin(0.01f).of(10.8f)
        assertThat(contrastRatio(1f, 0.49f)).isWithin(0.01f).of(1.94f)
        assertThat(darkReadsBetter(0.17f)).isFalse()
        assertThat(darkReadsBetter(0.19f)).isTrue()
        assertThat(darkReadsBetter(0f)).isFalse()
        assertThat(darkReadsBetter(1f)).isTrue()
        val crossover = 0.1791f
        assertThat(contrastRatio(0f, crossover)).isWithin(0.01f).of(contrastRatio(1f, crossover))
    }
}
