package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
import org.arkikeskus.launcher.ui.dimmedLuminance
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.junit.Test

/**
 * The drawer's background colour (a Telegram request after 0.9.9): the theme's scheme when none is
 * chosen and its text already reads right; a chosen colour becomes every surface slot the drawer
 * draws with, its text black or white by what the drawer really looks like -- the colour at its
 * opacity over the wallpaper -- and its chips and fields that text blended lightly into the colour.
 */
class DrawerColorSchemeTest {
    private val lightTheme = lightColorScheme()
    private val darkTheme = darkColorScheme()
    private val yellow = Color(0xFFFFEE58)
    private val navy = Color(0xFF0D1B3E)
    private val darkWallpaper = 0f
    private val lightWallpaper = dimmedLuminance(1f, 0.32f) // white under the shell's 32 % dim, as the screen shows it (0.42)

    /** A solid drawer: the colour alone decides. */
    @Test fun aSolidColourDecidesByItself() {
        assertThat(drawerTextIsDark(yellow, opacity = 1f, behindLuminance = darkWallpaper)).isTrue()
        assertThat(drawerTextIsDark(navy, opacity = 1f, behindLuminance = lightWallpaper)).isFalse()
    }

    /** The owner's case: yellow at 10 % over a dark wallpaper is a dark drawer and takes white text; over a
     *  light wallpaper it stays a light one. */
    @Test fun aSeeThroughColourCountsWhatShowsThroughIt() {
        assertThat(drawerTextIsDark(yellow, opacity = 0.1f, behindLuminance = darkWallpaper)).isFalse()
        assertThat(drawerTextIsDark(yellow, opacity = 0.1f, behindLuminance = lightWallpaper)).isTrue()
        assertThat(drawerTextIsDark(navy, opacity = 0.1f, behindLuminance = lightWallpaper)).isTrue()
        assertThat(drawerTextIsDark(yellow, opacity = 2f, behindLuminance = darkWallpaper)).isTrue() // clamped
    }

    /** The owner's 1.0.1 ask: a chosen label colour replaces the automatic black or white on the grid's labels, and
     *  only a chosen one does. */
    @Test fun aChosenLabelColourReplacesTheAutomaticOne() {
        assertThat(drawerLabelColor(chosen = null, automatic = Color.White)).isEqualTo(Color.White)
        assertThat(drawerLabelColor(chosen = 0xFF42A5F5.toInt(), automatic = Color.White)).isEqualTo(Color(0xFF42A5F5))
    }

    @Test fun noColourAndTheRightTextKeepsTheThemesScheme() {
        assertThat(drawerColorScheme(lightTheme, null, textIsDark = true)).isSameInstanceAs(lightTheme)
        assertThat(drawerColorScheme(darkTheme, null, textIsDark = false)).isSameInstanceAs(darkTheme)
    }

    /** The drawer's OPAQUE surfaces (the folder editor sheet, the add-apps dialog) sit on their own solid colour, so
     *  their text follows that colour alone: a light theme's sheet keeps dark text even while the see-through drawer
     *  around it, over a dark picture, has turned its text white (and the other way round for a dark theme). */
    @Test fun theOpaqueSurfacesKeepTheirOwnText() {
        assertThat(opaqueDrawerColorScheme(lightTheme, null)).isSameInstanceAs(lightTheme)
        assertThat(opaqueDrawerColorScheme(darkTheme, null)).isSameInstanceAs(darkTheme)
        val onYellow = opaqueDrawerColorScheme(darkTheme, yellow)
        assertThat(onYellow.surface).isEqualTo(yellow)
        assertThat(onYellow.onSurface).isEqualTo(Color.Black)
        val onNavy = opaqueDrawerColorScheme(lightTheme, navy)
        assertThat(onNavy.surface).isEqualTo(navy)
        assertThat(onNavy.onSurface).isEqualTo(Color.White)
    }

    /** A light theme's see-through drawer over a dark wallpaper: no colour chosen, but the text must turn light. */
    @Test fun noColourButTheWrongTextFlipsTheThemesText() {
        val scheme = drawerColorScheme(lightTheme, null, textIsDark = false)
        assertThat(scheme.surface).isEqualTo(lightTheme.surface)
        assertThat(scheme.onSurface).isEqualTo(Color.White)
        assertThat(scheme.surfaceVariant.luminance()).isGreaterThan(lightTheme.surface.luminance() - 0.01f)
    }

    @Test fun aLightColourTakesDarkText() {
        val scheme = drawerColorScheme(lightTheme, yellow, textIsDark = true)

        assertThat(scheme.surface).isEqualTo(yellow)
        assertThat(scheme.background).isEqualTo(yellow)
        assertThat(scheme.onSurface).isEqualTo(Color.Black)
        assertThat(scheme.onBackground).isEqualTo(Color.Black)
        // Chips and fields: the text tinted lightly into the colour, so they still read as the colour but stand off it.
        assertThat(scheme.surfaceVariant.luminance()).isLessThan(yellow.luminance())
        assertThat(scheme.surfaceContainerHighest.luminance()).isLessThan(scheme.surfaceContainerLow.luminance())
        assertThat(scheme.onSurfaceVariant.luminance()).isGreaterThan(Color.Black.luminance())
        assertThat(scheme.onSurfaceVariant.luminance()).isLessThan(yellow.luminance())
    }

    @Test fun aDarkColourTakesLightText() {
        val scheme = drawerColorScheme(lightTheme, navy, textIsDark = false)

        assertThat(scheme.surface).isEqualTo(navy)
        assertThat(scheme.onSurface).isEqualTo(Color.White)
        assertThat(scheme.surfaceVariant.luminance()).isGreaterThan(navy.luminance())
        assertThat(scheme.outline.luminance()).isGreaterThan(scheme.outlineVariant.luminance())
    }

    /** The WCAG contrast ratio of two colours by their relative luminance, 1 (none) to 21 (black on white). */
    private fun contrast(a: Color, b: Color): Float {
        val l1 = maxOf(a.luminance(), b.luminance())
        val l2 = minOf(a.luminance(), b.luminance())
        return (l1 + 0.05f) / (l2 + 0.05f)
    }

    /** The swatches, solid: each takes the text that contrasts more with it -- the round-7 finding: orange #FFA726
     *  (relative luminance 0.49, under the old 0.5 "mid grey") took white at 1.9:1 where black reads 10.8:1, and so
     *  did green, blue and red. Black contrasts more down to a relative luminance of 0.18 (L* 50, the perceptual mid
     *  grey); purple #AB47BC (0.17) still takes white (4.8:1 against black's 4.4:1). */
    @Test fun aSolidSwatchTakesTheTextThatContrastsMore() {
        val swatches = listOf(0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC)
        for (argb in swatches) {
            val swatch = Color(argb)
            val text = if (drawerTextIsDark(swatch, 1f, 0f)) Color.Black else Color.White
            val other = if (text == Color.Black) Color.White else Color.Black
            assertWithMessage("swatch %06X".format(argb and 0xFFFFFF)).that(contrast(text, swatch)).isAtLeast(contrast(other, swatch))
        }
        assertThat(drawerTextIsDark(Color(0xFFFFA726), 1f, 1f)).isTrue()
        assertThat(contrast(Color.Black, Color(0xFFFFA726))).isGreaterThan(4.5f)
        assertThat(drawerTextIsDark(Color(0xFFAB47BC), 1f, 0f)).isFalse()
    }

    /** The decision follows what is on screen: the window composites in sRGB (gamma) space, so a light theme's
     *  surface at 30 % over a dark wallpaper is a dark grey (#4B4B4B, relative luminance 0.07) that takes white --
     *  a linear mix of the luminances (0.29) would have put black on it at 2.4:1. At 60 % it is a light grey (0.31)
     *  and takes black. The user's own picture (mean relative luminance 0.5) under the black dim likewise: half
     *  dimmed it is dark and takes white, undimmed black. */
    @Test fun aSeeThroughSurfaceIsJudgedAsTheScreenComposesIt() {
        val lightSurface = Color(0xFFFBFCFF)
        assertThat(drawerTextIsDark(lightSurface, opacity = 0.3f, behindLuminance = darkWallpaper)).isFalse()
        assertThat(drawerTextIsDark(lightSurface, opacity = 0.6f, behindLuminance = darkWallpaper)).isTrue()
        assertThat(drawerTextIsDark(Color.Black, opacity = 0.5f, behindLuminance = 0.5f)).isFalse()
        assertThat(drawerTextIsDark(Color.Black, opacity = 0f, behindLuminance = 0.5f)).isTrue()
    }

    /** The round-8 finding: a black drawer at 40 % over a WHITE wallpaper under the shell's 32 % dim. The shell had mixed
     *  the dim linearly (0.68, where the screen shows sRGB 0.68 = 0.42), which this model read as too light a grey and
     *  put black text (3.8:1) on a dark grey that takes white (5.6:1). Fed what the screen shows, it takes white; at 20 %
     *  the grey is light enough (0.26) for black. */
    @Test fun aBlackDrawerOverTheDimmedWhiteWallpaperTakesWhite() {
        val dimmedWhite = dimmedLuminance(1f, 0.32f)
        assertThat(drawerTextIsDark(Color.Black, opacity = 0.4f, behindLuminance = dimmedWhite)).isFalse()
        assertThat(drawerTextIsDark(Color.Black, opacity = 0.2f, behindLuminance = dimmedWhite)).isTrue()
    }

    /** The search field and the folder cards draw from the expressive palette: on a chosen colour their
     *  backgrounds are tinted from the colour itself (the first build left a dark slab on a yellow drawer), with the
     *  light palette's text on a light colour and the dark palette's on a dark one. They are opaque, so the colour
     *  alone decides -- a yellow pill keeps dark text on a 10 % yellow drawer whose labels are white. */
    @Test fun theExpressivePaletteFollowsTheColourItself() {
        assertThat(drawerExpressivePalette(DarkExpressivePalette, null)).isSameInstanceAs(DarkExpressivePalette)
        assertThat(drawerExpressivePalette(LightExpressivePalette, null)).isSameInstanceAs(LightExpressivePalette)

        val onYellow = drawerExpressivePalette(DarkExpressivePalette, yellow)
        assertThat(onYellow.text).isEqualTo(LightExpressivePalette.text)
        assertThat(onYellow.bg).isEqualTo(yellow)
        assertThat(onYellow.surfaceHi.luminance()).isLessThan(yellow.luminance())
        assertThat(onYellow.surfaceHi.luminance()).isGreaterThan(0.4f)

        val onNavy = drawerExpressivePalette(LightExpressivePalette, navy)
        assertThat(onNavy.text).isEqualTo(DarkExpressivePalette.text)
        assertThat(onNavy.surfaceHi.luminance()).isGreaterThan(navy.luminance())
    }

    /** The round-9 finding: the search field, the folder cards and the work-profile card draw their text from the
     *  palette on their own tinted surface (surfaceHi), and the base palettes' fixed greys were made for near-white or
     *  near-black cards: on red #EF5350 the light palette's dim #5B616A read 1.4:1 on the darkened red card, on purple
     *  #AB47BC the dark palette's text 3.3:1. Now the card keeps the drawer's text family (black on red, white on
     *  purple -- one rule with the labels) and takes the tone that carries it: tinted toward the text as before, or
     *  away from it when that would leave the text under 4.5:1 (red lightens, purple darkens); dim and faint are the
     *  text blended into the final card. Every offered swatch: text at least 4.5:1, dim 3.5:1, the card distinct. Round
     *  10: the accent (the calculator result, the settings results' titles, icon tints) vanished on a red card (1.0:1)
     *  -- it is pushed toward the text until it reads, 4.5:1 on every swatch's card. */
    @Test fun theCardsTextReadsOnEveryOfferedSwatch() {
        val swatches = listOf(0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC)
        for (argb in swatches) {
            val colour = Color(argb)
            val p = drawerExpressivePalette(DarkExpressivePalette, colour)
            val name = "swatch %06X".format(argb and 0xFFFFFF)
            assertWithMessage("$name text").that(contrast(p.text, p.surfaceHi)).isAtLeast(4.5f)
            assertWithMessage("$name dim").that(contrast(p.dim, p.surfaceHi)).isAtLeast(3.5f)
            assertWithMessage("$name faint").that(contrast(p.faint, p.surfaceHi)).isAtLeast(1.5f)
            assertWithMessage("$name accent").that(contrast(p.accent, p.surfaceHi)).isAtLeast(4.5f)
            assertWithMessage("$name family").that(p.text.luminance() < 0.5f).isEqualTo(drawerTextIsDark(colour, 1f, 0f))
            assertWithMessage("$name card").that(contrast(p.surfaceHi, p.bg)).isAtLeast(1.1f)
            assertWithMessage("$name same from either system palette").that(drawerExpressivePalette(LightExpressivePalette, colour)).isEqualTo(p)
        }
        val red = Color(0xFFEF5350)
        val purple = Color(0xFFAB47BC)
        assertThat(drawerExpressivePalette(LightExpressivePalette, red).surfaceHi.luminance()).isGreaterThan(red.luminance())
        assertThat(drawerExpressivePalette(LightExpressivePalette, purple).surfaceHi.luminance()).isLessThan(purple.luminance())
    }

    /** The round-10 finding: the Material scheme the drawer's windows take (the folder editor, the add-apps dialog, the
     *  delete confirmation) tinted its containers toward the text and derived onSurfaceVariant from the plain surface,
     *  so on purple #AB47BC a dialog's body (onSurfaceVariant #E7CBEC on surfaceContainerHigh #B55DC4) read 2.7:1. Now
     *  the containers take the tone that carries the text -- toward it unless the strongest container would leave the
     *  text under 4.5:1, then away (purple's dialogs darken, red's lighten) -- and the secondary text is pushed toward
     *  the text until it reads on the container nearest the text. Every offered swatch, every container. Round 11: the
     *  PRIMARY (the dialogs' text buttons, the focused field, the checkboxes) stayed the theme's -- the light theme's
     *  #1B53C0 read 1.2:1 on purple's dialog, the dark theme's #B0C6FF 1.3:1 on white's -- so it is pushed toward the
     *  text like the secondary text, with its own on-colour by contrast; and both system themes are covered. */
    @Test fun theMaterialSchemeReadsOnEveryOfferedSwatch() {
        val swatches = listOf(0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, 0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC)
        for (base in listOf(lightTheme, darkTheme)) for (argb in swatches) {
            val colour = Color(argb)
            val s = opaqueDrawerColorScheme(base, colour)
            val name = "swatch %06X on the %s theme".format(argb and 0xFFFFFF, if (base === lightTheme) "light" else "dark")
            val containers = mapOf(
                "surface" to s.surface, "surfaceVariant" to s.surfaceVariant, "low" to s.surfaceContainerLow,
                "container" to s.surfaceContainer, "high" to s.surfaceContainerHigh, "highest" to s.surfaceContainerHighest,
            )
            for ((slot, container) in containers) {
                assertWithMessage("$name onSurface on $slot").that(contrast(s.onSurface, container)).isAtLeast(4.5f)
                assertWithMessage("$name onSurfaceVariant on $slot").that(contrast(s.onSurfaceVariant, container)).isAtLeast(4.5f)
                assertWithMessage("$name primary on $slot").that(contrast(s.primary, container)).isAtLeast(4.5f)
            }
            assertWithMessage("$name onPrimary on primary").that(contrast(s.onPrimary, s.primary)).isAtLeast(4.5f)
            assertWithMessage("$name dialog distinct").that(contrast(s.surfaceContainerHigh, s.surface)).isAtLeast(1.1f)
            assertWithMessage("$name family").that(s.onSurface).isEqualTo(if (drawerTextIsDark(colour, 1f, 0f)) Color.Black else Color.White)
        }
        val red = Color(0xFFEF5350)
        val purple = Color(0xFFAB47BC)
        assertThat(opaqueDrawerColorScheme(lightTheme, red).surfaceContainerHigh.luminance()).isGreaterThan(red.luminance())
        assertThat(opaqueDrawerColorScheme(lightTheme, purple).surfaceContainerHigh.luminance()).isLessThan(purple.luminance())
    }

    /** The theme's secondary and error slots stay (the Personal/Work tabs, errors); the PRIMARY follows the colour
     *  since round 11 (the light theme's #1B53C0 read 1.2:1 on purple's dialog) -- and stays the theme's own when no
     *  colour is chosen. */
    @Test fun theSecondaryAndErrorSlotsStayTheThemesThePrimaryReads() {
        val scheme = drawerColorScheme(lightTheme, Color(0xFF42A5F5), textIsDark = true)
        assertThat(scheme.secondaryContainer).isEqualTo(lightTheme.secondaryContainer)
        assertThat(scheme.error).isEqualTo(lightTheme.error)
        assertThat(contrast(scheme.primary, scheme.surfaceContainerHigh)).isAtLeast(4.5f)
        assertThat(contrast(scheme.onPrimary, scheme.primary)).isAtLeast(4.5f)
        assertThat(drawerColorScheme(lightTheme, null, textIsDark = false).primary).isEqualTo(lightTheme.primary)
        assertThat(opaqueDrawerColorScheme(darkTheme, null).primary).isEqualTo(darkTheme.primary)
    }
}
