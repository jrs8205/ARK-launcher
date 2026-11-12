package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** What a palette is computed from: one hue of the colour circle, or no colour at all. */
sealed interface PaletteSeed {
    /** [hue] in degrees (any value, wrapped into the circle), [saturation] of the fill, 0..1. */
    data class Hue(val hue: Float, val saturation: Float = 0.96f) : PaletteSeed

    /** The monochrome look: black, white and greys. */
    data object Mono : PaletteSeed
}

/**
 * Every colour a themed surface of the launcher takes, computed from one [PaletteSeed].
 *
 * The colour sits in the fills: [fill] (a switch that is on, a filled button, the selected tab, an
 * icon chip) with [onFill] on it, and [container] (a search field, a tonal button, a menu item)
 * with [onContainer]. [background] and [card] carry a tint of the same hue; [text] and [dim] are
 * what is read on them, [faint] only decorates. A switch that is off is told by its [offThumb],
 * not by its [track].
 */
@Immutable
data class BasePalette(
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
)

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
fun basePalette(seed: PaletteSeed, dark: Boolean): BasePalette =
    if (seed is PaletteSeed.Hue && seed.hue.isFinite() && seed.saturation.isFinite()) {
        tinted(((seed.hue % 360f) + 360f) % 360f, seed.saturation.coerceIn(0f, 1f), dark)
    } else {
        mono(dark)
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
    val background = if (dark) tone(0.45f, 0.06f) else tone(0.80f, 0.97f)
    val card = if (dark) tone(0.38f, 0.14f) else tone(0.85f, 0.92f)
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
        container = if (dark) tone(0.78f, (32 - step) / 100f) else tone(1f, (80 + step) / 100f)
        if (contrast(onContainer, container) >= TEXT_CONTRAST) break
    }

    return BasePalette(
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

private val DarkError = Color(0xFFFFB4AB)
private val LightError = Color(0xFFBA1A1A)
