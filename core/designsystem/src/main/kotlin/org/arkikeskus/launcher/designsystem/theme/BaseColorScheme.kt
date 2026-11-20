package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The Material scheme of this palette, with every role set: what Material's own components
 * (dialogs, sheets, chips, text fields) read is the same colour the hand-made ones do.
 *
 * `primary` is the fill where the fill reads as text, because Material writes a dialog's text
 * buttons in `primary` on a surface; a fill only has to stand 3:1 from the card, so for the hues
 * where that is not enough (blue on dark) `primary` is the fill moved toward the text until it
 * reads on every surface level. There is one colour: secondary and tertiary are primary.
 * `onSurfaceVariant` is [BasePalette.dim] by the same rule: itself where it reads on every surface
 * level, otherwise moved toward the text until it does.
 */
fun BasePalette.toColorScheme(): ColorScheme {
    fun towardText(color: Color, fraction: Float) = text.copy(alpha = fraction).compositeOver(color)
    val low = card.copy(alpha = 0.5f).compositeOver(background)
    val high = towardText(card, 0.06f)
    val highest = towardText(card, 0.12f)
    val surfaces = listOf(background, low, card, high, highest)

    // The descriptions' colour, as text on every surface level: [dim] itself where it reads on all of them. It
    // is made to read on the page and the card; on the lightest dark level, near yellow, it falls a hair short.
    val variantText = readable(dim, text, surfaces)
    val primary = readable(fill, text, surfaces)
    val onPrimary = contentOn(primary, preferred = onFill)
    val errorText = readable(error, text, surfaces)
    val errorContainer = card.copy(alpha = 0.8f).compositeOver(errorText)

    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = container,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = high,
        onSurfaceVariant = variantText,
        surfaceTint = primary,
        inverseSurface = text,
        inverseOnSurface = background,
        error = errorText,
        onError = contentOn(errorText, preferred = background),
        errorContainer = errorContainer,
        onErrorContainer = text,
        outline = dim,
        outlineVariant = track,
        scrim = Color.Black,
        surfaceBright = if (dark) highest else background,
        surfaceDim = if (dark) background else high,
        surfaceContainerLowest = background,
        surfaceContainerLow = low,
        surfaceContainer = card,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
        primaryFixed = container,
        primaryFixedDim = container,
        onPrimaryFixed = onContainer,
        onPrimaryFixedVariant = onContainer,
        secondaryFixed = container,
        secondaryFixedDim = container,
        onSecondaryFixed = onContainer,
        onSecondaryFixedVariant = onContainer,
        tertiaryFixed = container,
        tertiaryFixedDim = container,
        onTertiaryFixed = onContainer,
        onTertiaryFixedVariant = onContainer,
    )
}

/** [preferred] where it reads as text on [surface], otherwise black or white, whichever reads better. */
private fun contentOn(surface: Color, preferred: Color): Color = when {
    contrast(preferred, surface) >= 4.5f -> preferred
    contrast(Color.Black, surface) >= contrast(Color.White, surface) -> Color.Black
    else -> Color.White
}
