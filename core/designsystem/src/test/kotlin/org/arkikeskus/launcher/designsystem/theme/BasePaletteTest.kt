package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The palette every screen is coloured from: for every hue of the circle and for the monochrome
 * seed, in dark and light, whatever carries text or tells a control's state must read.
 */
class BasePaletteTest {
    // Every whole hue at every strength a seed can have: the presets use 0.85, 0.90, 0.96 and 1, and a
    // seed built by hand may carry any other.
    private val seeds: List<PaletteSeed> = listOf(0f, 0.3f, 0.6f, 0.85f, 0.9f, 0.96f, 1f)
        .flatMap { s -> (0 until 360).map { PaletteSeed.Hue(it.toFloat(), s) } } + PaletteSeed.Mono

    private fun everyPalette(check: (BasePalette, String) -> Unit) =
        seeds.forEach { s -> listOf(true, false).forEach { d -> check(basePalette(s, d), "$s dark=$d") } }

    @Test fun `text and descriptions read on the page and on a card`() = everyPalette { p, at ->
        listOf(p.background, p.card).forEach { surface ->
            assertWithMessage(at).that(contrast(p.text, surface)).isAtLeast(4.5f)
            assertWithMessage(at).that(contrast(p.dim, surface)).isAtLeast(4.5f)
        }
    }

    @Test fun `content on a fill and on a container reads`() = everyPalette { p, at ->
        assertWithMessage(at).that(contrast(p.onFill, p.fill)).isAtLeast(4.5f)
        assertWithMessage(at).that(contrast(p.onContainer, p.container)).isAtLeast(4.5f)
    }

    @Test fun `a fill and an off thumb stand out from what they sit on`() = everyPalette { p, at ->
        assertWithMessage(at).that(contrast(p.fill, p.card)).isAtLeast(3f)
        assertWithMessage(at).that(contrast(p.fill, p.background)).isAtLeast(3f)
        assertWithMessage(at).that(contrast(p.offThumb, p.card)).isAtLeast(3f)
    }

    @Test fun `the accent text and the error read on the page and on a card`() = everyPalette { p, at ->
        listOf(p.background, p.card).forEach { surface ->
            assertWithMessage(at).that(contrast(p.accentTextOn(surface), surface)).isAtLeast(4.5f)
            assertWithMessage(at).that(contrast(p.error, surface)).isAtLeast(4.5f)
        }
    }

    @Test fun `orange is the mock-up's orange`() {
        val dark = basePalette(PaletteSeed.Hue(15f), dark = true)
        assertNear(dark.fill, Color(0xFFFB602D))
        assertNear(dark.background, Color.hsl(15f, 0.45f, 0.06f))
        assertNear(dark.card, Color.hsl(15f, 0.38f, 0.14f))
        val light = basePalette(PaletteSeed.Hue(15f), dark = false)
        assertNear(light.fill, Color(0xFFDC3A04))
        assertThat(light.onFill).isEqualTo(Color.White)
    }

    @Test fun `yellow carries dark content in both modes and deepens in light`() {
        val dark = basePalette(PaletteSeed.Hue(46f, 1f), dark = true)
        val light = basePalette(PaletteSeed.Hue(46f, 1f), dark = false)
        assertThat(dark.onFill.luminance()).isLessThan(0.05f)
        assertThat(light.onFill.luminance()).isLessThan(0.05f)
        assertThat(light.fill.luminance()).isLessThan(dark.fill.luminance())
    }

    @Test fun `black has no tint and swaps its fill with the mode`() {
        val dark = basePalette(PaletteSeed.Mono, dark = true)
        assertThat(dark.background).isEqualTo(Color.Black)
        assertThat(dark.fill.luminance()).isGreaterThan(0.85f)
        assertThat(dark.onFill).isEqualTo(Color.Black)
        val light = basePalette(PaletteSeed.Mono, dark = false)
        assertThat(light.fill.luminance()).isLessThan(0.02f)
        assertThat(light.onFill).isEqualTo(Color.White)
        listOf(dark, light).flatMap { listOf(it.background, it.card, it.text, it.dim, it.track, it.container, it.fill) }
            .forEach {
                assertThat(it.red).isEqualTo(it.green)
                assertThat(it.green).isEqualTo(it.blue)
            }
    }

    @Test fun `a hue outside the circle wraps and a hue that is not a number is monochrome`() {
        assertThat(basePalette(PaletteSeed.Hue(375f), true)).isEqualTo(basePalette(PaletteSeed.Hue(15f), true))
        assertThat(basePalette(PaletteSeed.Hue(-10f), false)).isEqualTo(basePalette(PaletteSeed.Hue(350f), false))
        assertThat(basePalette(PaletteSeed.Hue(Float.NaN), true)).isEqualTo(basePalette(PaletteSeed.Mono, true))
    }

    private fun assertNear(actual: Color, expected: Color) {
        listOf(actual.red to expected.red, actual.green to expected.green, actual.blue to expected.blue)
            .forEach { (a, e) -> assertThat(a).isWithin(2f / 255f).of(e) }
    }
}
