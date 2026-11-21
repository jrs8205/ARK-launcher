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
import org.arkikeskus.launcher.designsystem.theme.toColorScheme
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.toExpressive
import org.arkikeskus.launcher.ui.srgbGrey
import org.junit.Test

/**
 * A Telegram request after 1.2.0: the drawer's search field is as see-through as the drawer itself. On a solid
 * drawer it is the opaque pill it always was. See-through, its own colour no longer decides its text: what shows
 * through it does, so the text must read on the field as the screen shows it, whatever the drawer's colour, its
 * opacity and the wallpaper behind it are. The same goes for the text selection: the highlight behind selected text
 * and the handles were the theme's own whatever the field looked like, and selected text read 2.7:1 on one drawer.
 */
class DrawerSearchFieldColoursTest {
    // The nine colours Settings offers for the drawer's background, and the theme's own (null).
    private val swatches: List<Long?> = listOf(
        null, 0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC,
    )
    private val seeThrough = listOf(0f, 0.1f, 0.25f, 0.3f, 0.5f, 0.7f, 0.9f, 0.99f)

    // Behind the drawer: a black wallpaper, dark ones, the mid grey where black and white text swap, a light one, white.
    private val behind = listOf(0f, 0.05f, 0.11f, 0.18f, 0.5f, 1f)

    private val seeds = listOf(
        "orange" to DefaultPaletteSeed, "blue" to PaletteSeed.Hue(220f), "black" to PaletteSeed.Mono, "classic" to PaletteSeed.Classic,
    )

    private fun ratio(a: Color, b: Color) = contrastRatio(a.luminance(), b.luminance())

    private fun drawerPalette(base: BasePalette, background: Color?): ExpressivePalette =
        drawerExpressivePalette(base.toExpressive(), background) { dark -> base.inMode(dark).toExpressive() }

    /**
     * One drawer as the screen builds it: the user's colour ([background], null = the theme's), or with the user's own
     * [picture] the black dim over it and no colour.
     */
    private inner class Drawer(val at: String, base: BasePalette, val background: Color?, picture: Boolean) {
        private val theme = base.toColorScheme()
        val palette = drawerPalette(base, background)
        private val paint: Color = if (picture) Color.Black else background ?: theme.surface

        /** The drawer at an [opacity] over a wallpaper of [behindLuminance]. */
        fun look(opacity: Float, behindLuminance: Float) = Look("$at, opacity $opacity, behind $behindLuminance").apply {
            // The theme's selection colour is the primary of the drawer's own Material scheme.
            accent = drawerColorScheme(theme, background, drawerTextIsDark(paint, opacity, behindLuminance)).primary
            field = drawerSearchFieldColors(palette, paint, opacity, behindLuminance, accent)
            // The field on screen: its colour over the drawer's over a grey of the wallpaper.
            val grey = srgbGrey(behindLuminance)
            shown = field.container.compositeOver(paint.copy(alpha = opacity).compositeOver(Color(grey, grey, grey)))
        }
    }

    /** What one drawer looks like: the field's colours, the theme's [accent], and the field as the screen [shown] it. */
    private class Look(val at: String) {
        lateinit var field: SearchFieldColors
        var accent = Color.Unspecified
        var shown = Color.Unspecified

        /** The highlight behind selected text, over the field. */
        val highlighted get() = field.selectionBackground.compositeOver(shown)
    }

    private fun everyDrawer(check: (Drawer) -> Unit) {
        for ((name, seed) in seeds) for (dark in listOf(true, false)) {
            val base = basePalette(seed, dark)
            val phone = "base $name, phone ${if (dark) "dark" else "light"}"
            for (argb in swatches) {
                val colour = argb?.let { "#%06X".format(it and 0xFFFFFF) } ?: "the theme's"
                check(Drawer("drawer $colour, $phone", base, argb?.let { Color(it) }, picture = false))
            }
            check(Drawer("the user's own picture, $phone", base, background = null, picture = true))
        }
    }

    /** Every look of every drawer: solid, and each see-through opacity over each wallpaper. */
    private fun everyLook(check: (Look) -> Unit) = everyDrawer { drawer ->
        for (opacity in seeThrough + 1f) for (l in behind) check(drawer.look(opacity, l))
    }

    @Test fun onASolidDrawerTheFieldIsThePalettesOwnOpaquePill() = everyDrawer { drawer ->
        for (l in behind) {
            val field = drawer.look(opacity = 1f, behindLuminance = l).field
            assertWithMessage(drawer.at).that(field.container).isEqualTo(drawer.palette.container)
            assertWithMessage(drawer.at).that(field.content).isEqualTo(drawer.palette.onContainer)
        }
    }

    @Test fun theFieldsTextReadsOnWhatShowsThroughIt() = everyLook { look ->
        assertWithMessage("${look.at}: the text on the field as shown").that(ratio(look.field.content, look.shown)).isAtLeast(4.5f)
    }

    @Test fun theFieldIsAsSeeThroughAsTheDrawer() = everyDrawer { drawer ->
        for (opacity in listOf(0.3f, 0.5f, 0.9f)) {
            val field = drawer.look(opacity, behindLuminance = 0.5f).field
            assertWithMessage("${drawer.at}, opacity $opacity").that(field.container.alpha).isWithin(0.01f).of(opacity)
            assertWithMessage(drawer.at).that(field.container.copy(alpha = 1f)).isEqualTo(drawer.palette.container.copy(alpha = 1f))
        }
    }

    /** A drawer that paints nothing ("Transparent", or the user's own picture without a dim): the pill still shows. */
    @Test fun onADrawerThatPaintsNothingTheFieldKeepsAFloor() = everyDrawer { drawer ->
        for (opacity in listOf(0f, 0.1f)) {
            val field = drawer.look(opacity, behindLuminance = 0.5f).field
            assertWithMessage("${drawer.at}, opacity $opacity").that(field.container.alpha).isWithin(0.01f).of(SEARCH_FIELD_MIN_OPACITY)
        }
    }

    /** The case the opaque pill never had: a light theme's field on a see-through drawer over a dark wallpaper is a
     *  dark field on screen and takes white text; over a light wallpaper it keeps black. */
    @Test fun aLightThemesFieldOverADarkWallpaperTakesWhiteText() {
        val drawer = Drawer("the theme's, light", basePalette(DefaultPaletteSeed, dark = false), background = null, picture = false)

        assertThat(drawer.look(opacity = 0.2f, behindLuminance = 0.02f).field.content).isEqualTo(Color.White)
        assertThat(drawer.look(opacity = 0.2f, behindLuminance = 0.9f).field.content).isEqualTo(Color.Black)
    }

    /** The user's own picture: the drawer paints black at the dim over it, and the field follows the dim. */
    @Test fun overTheUsersOwnPictureTheFieldFollowsTheDim() {
        val drawer = Drawer("picture, light", basePalette(DefaultPaletteSeed, dark = false), background = null, picture = true)

        assertThat(drawer.look(opacity = 0.6f, behindLuminance = 0.8f).field.container.alpha).isWithin(0.01f).of(0.6f)
        assertThat(drawer.look(opacity = 1f, behindLuminance = 0.8f).field.container).isEqualTo(drawer.palette.container)
    }

    // --- The text selection ------------------------------------------------------------------------------------

    /** Selected text is the field's text on the highlight, which lies over the field as shown. */
    @Test fun selectedTextReadsOnItsHighlight() = everyLook { look ->
        assertWithMessage("${look.at}: selected text on its highlight").that(ratio(look.field.content, look.highlighted)).isAtLeast(4.5f)
    }

    /** A handle is a control drawn on the field: it needs what a control needs against it. */
    @Test fun theSelectionHandleStandsOutFromTheField() = everyLook { look ->
        assertWithMessage("${look.at}: the handle on the field as shown").that(ratio(look.field.selectionHandle, look.shown)).isAtLeast(3f)
    }

    /** A highlight that melts into the field would read, and show nothing of what is selected. */
    @Test fun theHighlightShowsOnTheField() = everyLook { look ->
        assertWithMessage("${look.at}: the highlight against the field around it; ${look.field}, shown ${look.shown}")
            .that(ratio(look.highlighted, look.shown)).isAtLeast(1.2f)
    }

    /** The review's first case: a light phone, the Black base colour, the user's own even grey picture (luminance
     *  0.11) with no dim. Selected text read 2.69:1 on the theme's highlight. */
    @Test fun selectedTextReadsOverADarkGreyPictureWithTheBlackBaseColour() {
        val drawer = Drawer("picture, black, light", basePalette(PaletteSeed.Mono, dark = false), background = null, picture = true)
        val look = drawer.look(opacity = 0f, behindLuminance = 0.11f)

        // What it was: the theme's own highlight under the same text.
        val themes = look.accent.copy(alpha = SELECTION_ALPHA).compositeOver(look.shown)
        assertThat(ratio(look.field.content, themes)).isLessThan(3f)
        assertThat(ratio(look.field.content, look.highlighted)).isAtLeast(4.5f)
    }

    /** Its second: a dark phone, the orange base colour, a white picture. The handle stood 1.61:1 from the field. */
    @Test fun theHandleStandsOutOverAWhitePictureWithTheOrangeBaseColour() {
        val drawer = Drawer("picture, orange, dark", basePalette(DefaultPaletteSeed, dark = true), background = null, picture = true)
        val look = drawer.look(opacity = 0f, behindLuminance = 1f)

        assertThat(ratio(look.accent, look.shown)).isLessThan(2f) // what it was: the theme's own handle
        assertThat(ratio(look.field.selectionHandle, look.shown)).isAtLeast(3f)
    }

    /** The theme's own selection colours stay the field's wherever they do read: nothing is changed that worked. */
    @Test fun theThemesOwnSelectionIsKeptWhereverItReads() {
        var keptHighlights = 0
        var keptHandles = 0
        everyLook { look ->
            val themes = look.accent.copy(alpha = SELECTION_ALPHA)
            val onScreen = themes.compositeOver(look.shown)
            if (ratio(look.field.content, onScreen) >= 4.5f && ratio(onScreen, look.shown) >= 1.2f) {
                assertWithMessage("${look.at}: the highlight").that(look.field.selectionBackground).isEqualTo(themes)
                keptHighlights++
            }
            if (ratio(look.accent, look.shown) >= 3f) {
                assertWithMessage("${look.at}: the handle").that(look.field.selectionHandle).isEqualTo(look.accent)
                keptHandles++
            }
        }
        // Not an empty promise: many looks keep them.
        assertThat(keptHighlights).isGreaterThan(100)
        assertThat(keptHandles).isGreaterThan(100)
    }

    /** The default drawer of a light phone is one of them: it looks as it did. */
    @Test fun theDefaultLightDrawerKeepsTheThemesSelection() {
        val drawer = Drawer("the theme's", basePalette(DefaultPaletteSeed, dark = false), background = null, picture = false)
        val look = drawer.look(opacity = 1f, behindLuminance = 0f)

        assertThat(look.field.selectionHandle).isEqualTo(look.accent)
        assertThat(look.field.selectionBackground).isEqualTo(look.accent.copy(alpha = SELECTION_ALPHA))
    }
}
