package org.arkikeskus.launcher.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The Material scheme the dialogs, pickers and sheets read, built from the base palette: every
 * pair Material draws as text on a surface must read, for every hue and for the monochrome seed.
 */
class BaseColorSchemeTest {
    // Every whole hue at every strength a seed can have: the presets use 0.85, 0.90, 0.96 and 1, and a
    // seed built by hand may carry any other.
    private val seeds: List<PaletteSeed> = listOf(0f, 0.3f, 0.6f, 0.85f, 0.9f, 0.96f, 1f)
        .flatMap { s -> (0 until 360).map { PaletteSeed.Hue(it.toFloat(), s) } } + PaletteSeed.Mono

    private fun everyPalette(check: (BasePalette, String) -> Unit) =
        seeds.forEach { s -> listOf(true, false).forEach { d -> check(basePalette(s, d), "$s dark=$d") } }

    private fun surfacesOf(s: ColorScheme): List<Color> = listOf(
        s.background, s.surface, s.surfaceVariant, s.surfaceDim, s.surfaceBright, s.surfaceContainerLowest,
        s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest,
    )

    @Test fun `every text role reads on every surface`() = everyPalette { p, at ->
        val s = p.toColorScheme()
        surfacesOf(s).forEach { surface ->
            assertWithMessage("$at onSurface").that(contrast(s.onSurface, surface)).isAtLeast(4.5f)
            assertWithMessage("$at onSurfaceVariant").that(contrast(s.onSurfaceVariant, surface)).isAtLeast(4.5f)
            // A dialog's text buttons are written in `primary` on a surface, and its error text in `error`.
            assertWithMessage("$at primary").that(contrast(s.primary, surface)).isAtLeast(4.5f)
            assertWithMessage("$at error").that(contrast(s.error, surface)).isAtLeast(4.5f)
            assertWithMessage("$at outline").that(contrast(s.outline, surface)).isAtLeast(3f)
        }
    }

    @Test fun `what is written on a coloured role reads`() = everyPalette { p, at ->
        val s = p.toColorScheme()
        listOf(
            "primary" to (s.onPrimary to s.primary),
            "primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "secondary" to (s.onSecondary to s.secondary),
            "secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            "tertiary" to (s.onTertiary to s.tertiary),
            "tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "error" to (s.onError to s.error),
            "errorContainer" to (s.onErrorContainer to s.errorContainer),
            "inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
            "primaryFixed" to (s.onPrimaryFixed to s.primaryFixed),
            "primaryFixedDim" to (s.onPrimaryFixedVariant to s.primaryFixedDim),
        ).forEach { (role, pair) ->
            assertWithMessage("$at $role").that(contrast(pair.first, pair.second)).isAtLeast(4.5f)
        }
    }

    @Test fun `the scheme is the palette`() {
        val p = basePalette(PaletteSeed.Hue(220f), dark = true)
        val s = p.toColorScheme()
        assertThat(s.background).isEqualTo(p.background)
        assertThat(s.surface).isEqualTo(p.background)
        assertThat(s.surfaceContainerLowest).isEqualTo(p.background)
        assertThat(s.surfaceContainer).isEqualTo(p.card)
        assertThat(s.onBackground).isEqualTo(p.text)
        assertThat(s.onSurface).isEqualTo(p.text)
        assertThat(s.onSurfaceVariant).isEqualTo(p.dim)
        assertThat(s.primaryContainer).isEqualTo(p.container)
        assertThat(s.onPrimaryContainer).isEqualTo(p.onContainer)
        assertThat(s.secondaryContainer).isEqualTo(p.container)
        assertThat(s.secondary).isEqualTo(s.primary)
        assertThat(s.tertiary).isEqualTo(s.primary)
        assertThat(s.surfaceTint).isEqualTo(s.primary)
        assertThat(s.scrim).isEqualTo(Color.Black)
    }

    @Test fun `primary is the fill itself wherever the fill already reads as text`() {
        var untouched = 0
        var moved = 0
        everyPalette { p, at ->
            val s = p.toColorScheme()
            if (surfacesOf(s).all { contrast(p.fill, it) >= 4.5f }) {
                assertWithMessage(at).that(s.primary).isEqualTo(p.fill)
                assertWithMessage(at).that(s.onPrimary).isEqualTo(p.onFill)
                untouched++
            } else {
                moved++
            }
        }
        // Both cases exist, so neither branch above is vacuous: yellow on dark reads as it is,
        // blue on dark does not.
        assertThat(untouched).isGreaterThan(0)
        assertThat(moved).isGreaterThan(0)
    }

    @Test fun `no role is left to Material's baseline`() {
        listOf(false to lightColorScheme(), true to darkColorScheme()).forEach { (dark, baseline) ->
            val s = basePalette(PaletteSeed.Hue(15f), dark).toColorScheme()
            assertThat(s.surfaceContainerHigh).isNotEqualTo(baseline.surfaceContainerHigh)
            assertThat(s.surfaceContainerHighest).isNotEqualTo(baseline.surfaceContainerHighest)
            assertThat(s.surfaceBright).isNotEqualTo(baseline.surfaceBright)
            assertThat(s.outlineVariant).isNotEqualTo(baseline.outlineVariant)
            assertThat(s.inverseSurface).isNotEqualTo(baseline.inverseSurface)
            assertThat(s.inversePrimary).isNotEqualTo(baseline.inversePrimary)
            assertThat(s.primaryFixed).isNotEqualTo(baseline.primaryFixed)
            assertThat(s.tertiaryFixedDim).isNotEqualTo(baseline.tertiaryFixedDim)
            assertThat(s.errorContainer).isNotEqualTo(baseline.errorContainer)
        }
    }
}
