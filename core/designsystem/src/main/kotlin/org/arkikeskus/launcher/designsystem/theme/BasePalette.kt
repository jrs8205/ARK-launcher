package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/** What a palette is computed from: one hue of the colour circle, no colour at all, or the look before 1.1.0. */
sealed interface PaletteSeed {
    /** [hue] in degrees (any value, wrapped into the circle), [saturation] of the fill, 0..1. */
    data class Hue(val hue: Float, val saturation: Float = 0.96f) : PaletteSeed

    /** The monochrome look: black, white and greys. */
    data object Mono : PaletteSeed

    /** The colours of the look before 1.1.0 (issue #28): neutral grey surfaces and one warm orange. */
    data object Classic : PaletteSeed
}

/**
 * Every colour a themed surface of the launcher takes, computed from one [PaletteSeed].
 *
 * The colour sits in the fills: [fill] (a switch that is on, a filled button, the selected tab, an
 * icon chip) with [onFill] on it, and [container] (a search field, a tonal button, a menu item)
 * with [onContainer]. [background] and [card] carry a tint of the same hue; [text] and [dim] are
 * what is read on them, [faint] only decorates. A switch that is off is told by its [offThumb],
 * not by its [track]. [seed] is what the palette was really made from (the hue wrapped into the
 * circle, the strength clamped), so the same colour can be had in the other mode ([inMode]).
 */
@Immutable
data class BasePalette(
    val seed: PaletteSeed,
    val dark: Boolean,
    val background: Color,
    val card: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val track: Color,
    val offThumb: Color,
    val fill: Color,
    val onFill: Color,
    val container: Color,
    val onContainer: Color,
    val error: Color,
) {
    /** The fill as text or an icon tint anywhere on the page or on a card: itself where it reads on
     *  both, otherwise blended toward [text] just far enough that it does. */
    val accentText: Color = readable(fill, text, listOf(background, card))

    /** This colour for a [dark] or a light screen. The app drawer asks: it picks its palette by the
     *  text its own background needs, which is not always the phone's mode. */
    fun inMode(dark: Boolean): BasePalette = if (dark == this.dark) this else basePalette(seed, dark)
}

/** The WCAG contrast ratio of two opaque colours, 1 (the same) to 21 (black on white). */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** The contrast normal text needs, and the one a control needs against what it sits on (WCAG AA). */
private const val TEXT_CONTRAST = 4.5f
private const val CONTROL_CONTRAST = 3f

/** The fill's lightness (in hundredths) before correction, and how far the correction may move it. */
private const val DARK_FILL_START = 58
private const val LIGHT_FILL_START = 44
private const val FILL_STEPS = 70
private const val CONTAINER_STEPS = 40

/**
 * The palette of [seed] for a dark or a light screen. A seed that is not a usable number (a hue
 * converted from a colour that had none) gives the monochrome palette rather than a random hue.
 */
fun basePalette(seed: PaletteSeed, dark: Boolean): BasePalette = when {
    seed is PaletteSeed.Classic -> classic(dark)
    seed is PaletteSeed.Hue && seed.hue.isFinite() && seed.saturation.isFinite() ->
        tinted(((seed.hue % 360f) + 360f) % 360f, seed.saturation.coerceIn(0f, 1f), dark)
    else -> mono(dark)
}

/**
 * The fill as a foreground on [surface] (a title, an icon tint): itself where it reads as text,
 * otherwise blended toward [BasePalette.text] just far enough that it does.
 */
fun BasePalette.accentTextOn(surface: Color): Color = readable(fill, text, listOf(surface))

/** [fg], or [fg] blended toward [toward] in twentieths until it reads as text on every one of [surfaces]. */
internal fun readable(fg: Color, toward: Color, surfaces: List<Color>): Color {
    for (step in 0..20) {
        val candidate = if (step == 0) fg else toward.copy(alpha = step / 20f).compositeOver(fg)
        if (surfaces.all { contrast(candidate, it) >= TEXT_CONTRAST }) return candidate
    }
    return toward
}

private fun tinted(hue: Float, saturation: Float, dark: Boolean): BasePalette {
    fun tone(s: Float, l: Float) = Color.hsl(hue, s, l.coerceIn(0f, 1f))
    // The surfaces carry the base colour strongly (the owner's choice between two looks, 2026-11-19): a
    // page and a card that are clearly of the colour, not a grey with a hint of it.
    val background = if (dark) tone(0.60f, 0.07f) else tone(1.0f, 0.965f)
    val card = if (dark) tone(0.58f, 0.17f) else tone(1.0f, 0.90f)
    val text = if (dark) tone(0.30f, 0.95f) else tone(0.50f, 0.08f)
    val dim = if (dark) tone(0.22f, 0.72f) else tone(0.25f, 0.30f)

    // The fill starts vivid and moves away from the card (lighter on dark, deeper on light) until
    // it stands out from the card as a control and what is written on it reads. On light screens
    // that is why green, teal and yellow end up deeper than on dark ones.
    val nearBlack = tone(0.90f, 0.06f)
    var fill = card
    var onFill = text
    for (step in 0..FILL_STEPS) {
        val lightness = if (dark) DARK_FILL_START + step else LIGHT_FILL_START - step
        fill = tone(saturation, lightness / 100f)
        onFill = if (contrast(nearBlack, fill) >= contrast(Color.White, fill)) nearBlack else Color.White
        if (contrast(fill, card) >= CONTROL_CONTRAST && contrast(onFill, fill) >= TEXT_CONTRAST) break
    }

    // The container keeps its text's side of the screen; near yellow the dark one is too bright
    // for light text as tabled, so it moves the other way from the fill until the text reads.
    val onContainer = if (dark) tone(1f, 0.95f) else tone(0.95f, 0.12f)
    var container = card
    for (step in 0..CONTAINER_STEPS) {
        container = if (dark) tone(0.92f, (36 - step) / 100f) else tone(1f, (76 + step) / 100f)
        if (contrast(onContainer, container) >= TEXT_CONTRAST) break
    }

    return BasePalette(
        seed = PaletteSeed.Hue(hue, saturation),
        dark = dark,
        background = background,
        card = card,
        text = text,
        dim = dim,
        faint = if (dark) tone(0.16f, 0.52f) else tone(0.18f, 0.52f),
        track = if (dark) tone(0.30f, 0.28f) else tone(0.45f, 0.80f),
        offThumb = dim,
        fill = fill,
        onFill = onFill,
        container = container,
        onContainer = onContainer,
        error = readable(if (dark) DarkError else LightError, text, listOf(background, card)),
    )
}

/** Dark: a black page with white fills, because a black fill would not show on black. Light: the reverse. */
private fun mono(dark: Boolean): BasePalette {
    fun grey(level: Float) = Color(level, level, level)
    val background = if (dark) Color.Black else grey(0.98f)
    val card = grey(if (dark) 0.11f else 0.93f)
    val text = grey(if (dark) 0.96f else 0.07f)
    val dim = grey(if (dark) 0.70f else 0.30f)
    return BasePalette(
        seed = PaletteSeed.Mono,
        dark = dark,
        background = background,
        card = card,
        text = text,
        dim = dim,
        faint = grey(0.52f),
        track = grey(if (dark) 0.26f else 0.80f),
        offThumb = dim,
        fill = grey(if (dark) 0.96f else 0.08f),
        onFill = if (dark) Color.Black else Color.White,
        container = grey(if (dark) 0.22f else 0.84f),
        onContainer = grey(if (dark) 0.96f else 0.08f),
        error = readable(if (dark) DarkError else LightError, text, listOf(background, card)),
    )
}

/** The orange of the look before 1.1.0, and what is written on it. */
private val ClassicOrange = Color(0xFFE2714A)
private val ClassicOnOrange = Color(0xFF1A1C1E)

/**
 * The colours of the look before 1.1.0 (issue #28): the neutral greys its hand-made surfaces had, and its one
 * warm orange where the colour is. The surfaces, the texts and the orange are that look's own values. Where
 * that look did not keep what every palette here keeps, the rule wins and the colour stays as close as it allows:
 *
 * - it wrote white on the orange (3.1:1); the orange carries dark content;
 * - on a light screen the orange stood 2.9:1 from the card; there it is the same orange made deeper by
 *   hundredths, as far as it takes to stand out from the card and the page as a control;
 * - on a light screen a switch that was off had a white thumb on a light grey track; its thumb is the
 *   descriptions' grey, as in every other palette.
 *
 * On a light screen the card is the LIGHTER of the two surfaces, as it was: the one palette where it is.
 * A container (that look laid a veil of the text's colour over the card) is a grey of its own, told from both.
 */
private fun classic(dark: Boolean): BasePalette {
    val background = Color(if (dark) 0xFF0C0E13 else 0xFFECEEF2)
    val card = Color(if (dark) 0xFF1D222B else 0xFFF4F6F9)
    val text = Color(if (dark) 0xFFE9EBEE else 0xFF1A1C1E)
    val dim = Color(if (dark) 0xFF9AA1AB else 0xFF5B616A)
    var fill = ClassicOrange
    for (step in 0..FILL_STEPS) {
        fill = if (step == 0) ClassicOrange else Color.Black.copy(alpha = step / 100f).compositeOver(ClassicOrange)
        val standsOut = contrast(fill, card) >= CONTROL_CONTRAST && contrast(fill, background) >= CONTROL_CONTRAST
        if (standsOut && contrast(ClassicOnOrange, fill) >= TEXT_CONTRAST) break
    }
    return BasePalette(
        seed = PaletteSeed.Classic,
        dark = dark,
        background = background,
        card = card,
        text = text,
        dim = dim,
        faint = Color(if (dark) 0xFF6C7079 else 0xFF9499A1),
        track = Color(if (dark) 0xFF3A404A else 0xFFC8CCD3),
        offThumb = if (dark) Color(0xFFAEB4BD) else dim,
        fill = fill,
        onFill = ClassicOnOrange,
        container = Color(if (dark) 0xFF303640 else 0xFFD5DAE2),
        onContainer = text,
        error = readable(if (dark) DarkError else LightError, text, listOf(background, card)),
    )
}

private val DarkError = Color(0xFFFFB4AB)
private val LightError = Color(0xFFBA1A1A)

/** Below this saturation, or this close to black or white, a colour has no hue worth following. */
private const val COLOURLESS_SATURATION = 0.12f
private const val NEARLY_BLACK = 0.08f
private const val NEARLY_WHITE = 0.95f

/**
 * The seed the Chameleon choice gives for the wallpaper's main colour [wallpaperArgb]: its hue at
 * the presets' strength, whatever the wallpaper's own saturation, so the result is as vivid as the
 * other choices. A wallpaper without colour (grey, nearly black, nearly white, fully transparent)
 * and a wallpaper that reports none give the monochrome look, never a hue picked from noise.
 */
fun chameleonSeed(wallpaperArgb: Int?): PaletteSeed {
    if (wallpaperArgb == null || (wallpaperArgb ushr 24) == 0) return PaletteSeed.Mono
    val r = ((wallpaperArgb shr 16) and 0xFF) / 255f
    val g = ((wallpaperArgb shr 8) and 0xFF) / 255f
    val b = (wallpaperArgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val lightness = (max + min) / 2f
    if (lightness < NEARLY_BLACK || lightness > NEARLY_WHITE) return PaletteSeed.Mono
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    if (saturation < COLOURLESS_SATURATION) return PaletteSeed.Mono
    val sector = when (max) {
        r -> (g - b) / delta
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return PaletteSeed.Hue(((sector * 60f) + 360f) % 360f)
}
