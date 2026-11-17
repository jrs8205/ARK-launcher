package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.BasePalette
import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.toExpressive
import org.arkikeskus.launcher.ui.srgbGrey
import org.junit.Test

/**
 * A Telegram request after 1.2.0: the drawer's search field is as see-through as the drawer itself. On a solid
 * drawer it is the opaque pill it always was. See-through, its own colour no longer decides its text: what shows
 * through it does, so the text must read on the field as the screen shows it, whatever the drawer's colour, its
 * opacity and the wallpaper behind it are.
 */
class DrawerSearchFieldColoursTest {
    // The nine colours Settings offers for the drawer's background, and the theme's own (null).
    private val swatches: List<Long?> = listOf(
        null, 0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC,
    )
    private val opacities = listOf(0f, 0.1f, 0.25f, 0.3f, 0.5f, 0.7f, 0.9f, 0.99f)

    // Behind the drawer: a black wallpaper, a dark one, the mid grey where black and white text swap, a light one, white.
    private val behind = listOf(0f, 0.05f, 0.18f, 0.5f, 1f)

    private fun drawerPalette(base: BasePalette, background: Color?): ExpressivePalette =
        drawerExpressivePalette(base.toExpressive(), background) { dark -> base.inMode(dark).toExpressive() }

    /** What the screen shows where the field is: its colour over the drawer's over a grey of the wallpaper. */
    private fun shown(field: Color, drawer: Color, opacity: Float, behindLuminance: Float): Color {
        val grey = srgbGrey(behindLuminance)
        return field.compositeOver(drawer.copy(alpha = opacity).compositeOver(Color(grey, grey, grey)))
    }

    private fun everyDrawer(check: (at: String, palette: ExpressivePalette, drawer: Color) -> Unit) {
        val seeds = listOf("orange" to DefaultPaletteSeed, "blue" to PaletteSeed.Hue(220f), "black" to PaletteSeed.Mono)
        for (argb in swatches) for ((name, seed) in seeds) for (dark in listOf(true, false)) {
            val base = basePalette(seed, dark)
            val background = argb?.let { Color(it) }
            val palette = drawerPalette(base, background)
            val at = "drawer ${argb?.let { "#%06X".format(it and 0xFFFFFF) } ?: "the theme's"}, base $name, phone ${if (dark) "dark" else "light"}"
            // The theme's own drawer paints the theme's page colour.
            check(at, palette, background ?: palette.bg)
        }
    }

    @Test fun onASolidDrawerTheFieldIsThePalettesOwnOpaquePill() = everyDrawer { at, palette, drawer ->
        for (l in behind) {
            val field = drawerSearchFieldColors(palette, drawer, opacity = 1f, behindLuminance = l)
            assertWithMessage(at).that(field).isEqualTo(SearchFieldColors(palette.container, palette.onContainer))
        }
    }

    @Test fun theFieldsTextReadsOnWhatShowsThroughIt() = everyDrawer { at, palette, drawer ->
        for (opacity in opacities) for (l in behind) {
            val field = drawerSearchFieldColors(palette, drawer, opacity, l)
            val onScreen = shown(field.container, drawer, opacity, l)
            assertWithMessage("$at, opacity $opacity, behind $l: the text on the field as shown")
                .that(contrastRatio(field.content.luminance(), onScreen.luminance())).isAtLeast(4.5f)
        }
    }

    @Test fun theFieldIsAsSeeThroughAsTheDrawer() = everyDrawer { at, palette, drawer ->
        for (opacity in listOf(0.3f, 0.5f, 0.9f)) {
            val field = drawerSearchFieldColors(palette, drawer, opacity, behindLuminance = 0.5f)
            assertWithMessage("$at, opacity $opacity").that(field.container.alpha).isWithin(0.01f).of(opacity)
            assertWithMessage(at).that(field.container.copy(alpha = 1f)).isEqualTo(palette.container.copy(alpha = 1f))
        }
    }

    /** A drawer that paints nothing ("Transparent", or the user's own picture without a dim): the pill still shows. */
    @Test fun onADrawerThatPaintsNothingTheFieldKeepsAFloor() = everyDrawer { at, palette, drawer ->
        for (opacity in listOf(0f, 0.1f)) {
            val field = drawerSearchFieldColors(palette, drawer, opacity, behindLuminance = 0.5f)
            assertWithMessage("$at, opacity $opacity").that(field.container.alpha).isWithin(0.01f).of(SEARCH_FIELD_MIN_OPACITY)
        }
    }

    /** The case the opaque pill never had: a light theme's field on a see-through drawer over a dark wallpaper is a
     *  dark field on screen and takes white text; over a light wallpaper it keeps black. */
    @Test fun aLightThemesFieldOverADarkWallpaperTakesWhiteText() {
        val palette = drawerPalette(basePalette(DefaultPaletteSeed, dark = false), background = null)

        val overDark = drawerSearchFieldColors(palette, palette.bg, opacity = 0.2f, behindLuminance = 0.02f)
        val overLight = drawerSearchFieldColors(palette, palette.bg, opacity = 0.2f, behindLuminance = 0.9f)

        assertThat(overDark.content).isEqualTo(Color.White)
        assertThat(overLight.content).isEqualTo(Color.Black)
    }

    /** The user's own picture: the drawer paints black at the dim over it, and the field follows the dim. */
    @Test fun overTheUsersOwnPictureTheFieldFollowsTheDim() {
        val palette = drawerPalette(basePalette(DefaultPaletteSeed, dark = false), background = null)

        val dimmed = drawerSearchFieldColors(palette, Color.Black, opacity = 0.6f, behindLuminance = 0.8f)
        val solid = drawerSearchFieldColors(palette, Color.Black, opacity = 1f, behindLuminance = 0.8f)

        assertThat(dimmed.container.alpha).isWithin(0.01f).of(0.6f)
        assertThat(solid).isEqualTo(SearchFieldColors(palette.container, palette.onContainer))
    }
}
