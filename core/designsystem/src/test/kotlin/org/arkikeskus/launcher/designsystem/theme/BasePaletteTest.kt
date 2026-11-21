package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The palette every screen is coloured from: for every hue of the circle, for the monochrome seed and
 * for the Classic one, in dark and light, whatever carries text or tells a control's state must read.
 */
class BasePaletteTest {
    // Every whole hue at every strength a seed can have: the presets use 0.85, 0.90, 0.96 and 1, and a
    // seed built by hand may carry any other.
    private val seeds: List<PaletteSeed> = listOf(0f, 0.3f, 0.6f, 0.85f, 0.9f, 0.96f, 1f)
        .flatMap { s -> (0 until 360).map { PaletteSeed.Hue(it.toFloat(), s) } } + PaletteSeed.Mono + PaletteSeed.Classic

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
        val light = basePalette(PaletteSeed.Hue(15f), dark = false)
        assertNear(light.fill, Color(0xFFDC3A04))
        assertThat(light.onFill).isEqualTo(Color.White)
    }

    /**
     * The owner's choice of 2026-11-19, the more saturated of the two looks he compared: the page, the card and
     * the container carry clearly more of the base colour than they did (the dark card was 38 % saturated at
     * 14 % lightness, the light page 80 % at 97 %). Pinned on orange; the rules above hold it for every hue.
     */
    @Test fun `the surfaces carry the base colour as strongly as the owner chose`() {
        val dark = basePalette(PaletteSeed.Hue(15f), dark = true)
        assertNear(dark.background, Color.hsl(15f, 0.60f, 0.07f))
        assertNear(dark.card, Color.hsl(15f, 0.58f, 0.17f))
        assertNear(dark.container, Color.hsl(15f, 0.92f, 0.36f))
        val light = basePalette(PaletteSeed.Hue(15f), dark = false)
        assertNear(light.background, Color.hsl(15f, 1f, 0.965f))
        assertNear(light.card, Color.hsl(15f, 1f, 0.90f))
        assertNear(light.container, Color.hsl(15f, 1f, 0.76f))
    }

    /** More colour in the surfaces must not cost the order of the layers: the card is told from the page, and
     *  the container from the card, for every hue. */
    @Test fun `the card is told from the page and the container from the card`() = everyPalette { p, at ->
        if (p.seed is PaletteSeed.Mono) return@everyPalette
        // Classic's light card is the lighter of the two, as it was in the look it brings back (its own test below).
        if (p.seed is PaletteSeed.Classic && !p.dark) return@everyPalette
        if (p.dark) {
            assertWithMessage(at).that(p.card.luminance()).isGreaterThan(p.background.luminance())
            assertWithMessage(at).that(p.container.luminance()).isGreaterThan(p.card.luminance())
        } else {
            assertWithMessage(at).that(p.card.luminance()).isLessThan(p.background.luminance())
            assertWithMessage(at).that(p.container.luminance()).isLessThan(p.card.luminance())
        }
    }

    /**
     * Issue #28: the colours of the look before 1.1.0, as a choice. Its hand-made surfaces were neutral greys
     * with one warm orange, and these are that look's own values, so that whoever liked it finds it again.
     */
    @Test fun `classic has the greys and the orange of the look before 1_1_0`() {
        val orange = Color(0xFFE2714A)
        val dark = basePalette(PaletteSeed.Classic, dark = true)
        assertThat(dark.background).isEqualTo(Color(0xFF0C0E13))
        assertThat(dark.card).isEqualTo(Color(0xFF1D222B))
        assertThat(dark.text).isEqualTo(Color(0xFFE9EBEE))
        assertThat(dark.dim).isEqualTo(Color(0xFF9AA1AB))
        assertThat(dark.faint).isEqualTo(Color(0xFF6C7079))
        assertThat(dark.track).isEqualTo(Color(0xFF3A404A))
        assertThat(dark.offThumb).isEqualTo(Color(0xFFAEB4BD))
        // On a dark screen the orange is that look's very orange, as a fill and as text.
        assertThat(dark.fill).isEqualTo(orange)
        assertThat(dark.accentText).isEqualTo(orange)

        val light = basePalette(PaletteSeed.Classic, dark = false)
        assertThat(light.background).isEqualTo(Color(0xFFECEEF2))
        assertThat(light.card).isEqualTo(Color(0xFFF4F6F9))
        assertThat(light.text).isEqualTo(Color(0xFF1A1C1E))
        assertThat(light.dim).isEqualTo(Color(0xFF5B616A))
        assertThat(light.faint).isEqualTo(Color(0xFF9499A1))
        assertThat(light.track).isEqualTo(Color(0xFFC8CCD3))
        assertThat(dark.seed).isEqualTo(PaletteSeed.Classic)
        assertThat(light.seed).isEqualTo(PaletteSeed.Classic)
    }

    /**
     * Where that look did not keep the rules every palette keeps now, the rules win and the colour stays as close
     * as they allow. It wrote white on its orange (3.1:1): the orange carries dark content. On a light screen the
     * orange stood 2.9:1 from the card: there it is the same orange a shade deeper, and no further than it takes.
     * And a switch that was off had a white thumb on a light grey track: its thumb is the descriptions' grey.
     */
    @Test fun `classic keeps the rules where the earlier look did not`() {
        val orange = Color(0xFFE2714A)
        for (dark in listOf(true, false)) {
            val p = basePalette(PaletteSeed.Classic, dark)
            assertThat(p.onFill).isEqualTo(Color(0xFF1A1C1E))
            assertThat(contrast(p.onFill, p.fill)).isAtLeast(4.5f)
            assertThat(contrast(Color.White, orange)).isLessThan(4.5f)
        }
        val light = basePalette(PaletteSeed.Classic, dark = false)
        assertThat(contrast(orange, light.card)).isLessThan(3f)
        assertThat(contrast(light.fill, light.card)).isAtLeast(3f)
        assertThat(contrast(light.fill, light.background)).isAtLeast(3f)
        // The same colour, deeper: each channel a little lower, none of them far.
        assertThat(light.fill.red).isLessThan(orange.red)
        assertThat(light.fill.red).isGreaterThan(orange.red - 0.12f)
        assertThat(light.fill.green / light.fill.red).isWithin(0.01f).of(orange.green / orange.red)
        assertThat(light.fill.blue / light.fill.red).isWithin(0.01f).of(orange.blue / orange.red)
        assertThat(light.offThumb).isEqualTo(light.dim)
    }

    /** Its surfaces have no tint of the orange (that is what tells it from the Orange choice), and each is told
     *  from the next: on a light screen the card is the LIGHTER one, as it was, and a container is darker than both. */
    @Test fun `classic's surfaces are neutral and told from one another`() {
        for (dark in listOf(true, false)) {
            val p = basePalette(PaletteSeed.Classic, dark)
            for (surface in listOf(p.background, p.card, p.container, p.track)) {
                // A grey with at most a breath of blue: never warmer than it is cool.
                assertThat(surface.red).isAtMost(surface.blue)
                assertThat(surface.blue - surface.red).isLessThan(0.08f)
            }
            assertThat(p.onContainer).isEqualTo(p.text)
        }
        val dark = basePalette(PaletteSeed.Classic, dark = true)
        assertThat(dark.card.luminance()).isGreaterThan(dark.background.luminance())
        assertThat(dark.container.luminance()).isGreaterThan(dark.card.luminance())
        val light = basePalette(PaletteSeed.Classic, dark = false)
        assertThat(light.card.luminance()).isGreaterThan(light.background.luminance())
        assertThat(light.container.luminance()).isLessThan(light.background.luminance())
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
