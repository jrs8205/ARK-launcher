package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.BasePalette
import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.designsystem.theme.chameleonSeed
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.toExpressive
import org.junit.Test

/**
 * A drawer with the user's own background colour keeps its surfaces: the page, the cards, the fields
 * and the text come from that colour. The base colour only gives the fills there, and a fill must
 * still stand out and carry its content, whatever the two colours are, even when they are the same hue.
 */
class DrawerFillRulesTest {
    // The nine colours Settings offers for the drawer's background.
    private val swatches = listOf(
        0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC,
    )

    private fun ratio(a: Color, b: Color) = contrastRatio(a.luminance(), b.luminance())

    private fun drawerPalette(base: BasePalette, background: Color?): ExpressivePalette =
        drawerExpressivePalette(base.toExpressive(), background) { dark -> base.inMode(dark).toExpressive() }

    private fun everyDrawer(check: (String, ExpressivePalette) -> Unit) {
        for (argb in swatches) {
            val seeds = listOf(
                "orange" to DefaultPaletteSeed,
                "yellow" to PaletteSeed.Hue(46f, 1f),
                "blue" to PaletteSeed.Hue(220f),
                "black" to PaletteSeed.Mono,
                "the drawer's own hue" to chameleonSeed(argb.toInt()),
            )
            for ((name, seed) in seeds) for (dark in listOf(true, false)) {
                val at = "drawer #%06X, base $name, phone ${if (dark) "dark" else "light"}".format(argb and 0xFFFFFF)
                check(at, drawerPalette(basePalette(seed, dark), Color(argb)))
            }
        }
    }

    @Test fun aFillStandsOutFromTheDrawersCardAndPageAndCarriesItsContent() = everyDrawer { at, p ->
        assertWithMessage("$at: the fill on a card").that(ratio(p.fill, p.surfaceHi)).isAtLeast(3f)
        assertWithMessage("$at: the fill on the page").that(ratio(p.fill, p.bg)).isAtLeast(3f)
        assertWithMessage("$at: on the fill").that(ratio(p.onFill, p.fill)).isAtLeast(4.5f)
    }

    @Test fun theContainersAreTheDrawersOwnCardsNotTheBaseColours() = everyDrawer { at, p ->
        assertWithMessage(at).that(p.container).isEqualTo(p.surfaceHi)
        assertWithMessage(at).that(p.onContainer).isEqualTo(p.text)
        assertWithMessage("$at: on a container").that(ratio(p.onContainer, p.container)).isAtLeast(4.5f)
    }

    @Test fun theAccentTextReadsOnTheDrawersCard() = everyDrawer { at, p ->
        assertWithMessage(at).that(ratio(p.accent, p.surfaceHi)).isAtLeast(4.5f)
    }

    @Test fun withoutAChosenColourTheDrawerShowsTheBasePaletteAsItIs() {
        for (dark in listOf(true, false)) {
            val base = basePalette(PaletteSeed.Hue(182f), dark)
            assertThat(drawerPalette(base, null)).isEqualTo(base.toExpressive())
        }
    }

    @Test fun aFillThatAlreadyStandsOutIsTheBaseColoursOwn() {
        // Orange on a black drawer: the drawer takes light text, so the dark palette's vivid orange, untouched.
        val base = basePalette(DefaultPaletteSeed, dark = false)
        val p = drawerPalette(base, Color.Black)
        assertThat(p.fill).isEqualTo(base.inMode(dark = true).fill)
        assertThat(p.onFill).isEqualTo(base.inMode(dark = true).onFill)
    }

    @Test fun aFillOfTheDrawersOwnHueIsMovedUntilItShows() {
        // An orange drawer under the orange base colour: the fill as it is would sit on its own colour.
        val orangeDrawer = Color(0xFFFFA726)
        val base = basePalette(chameleonSeed(0xFFFFA726.toInt()), dark = false)
        val untouched = base.inMode(dark = false).fill
        val p = drawerPalette(base, orangeDrawer)
        assertThat(ratio(untouched, orangeDrawer)).isLessThan(3f)
        assertThat(p.fill).isNotEqualTo(untouched)
        assertThat(ratio(p.fill, p.bg)).isAtLeast(3f)
    }
}
