package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
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
    private val lightWallpaper = 0.68f // white under the shell's 32 % dim

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

    @Test fun noColourAndTheRightTextKeepsTheThemesScheme() {
        assertThat(drawerColorScheme(lightTheme, null, textIsDark = true)).isSameInstanceAs(lightTheme)
        assertThat(drawerColorScheme(darkTheme, null, textIsDark = false)).isSameInstanceAs(darkTheme)
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

    /** The label swatches, solid: white, grey and yellow read dark text; black, red, orange (relative luminance
     *  0.48), green, blue and purple light. */
    @Test fun theSolidSwatchesSplitAsExpected() {
        assertThat(listOf(0xFFFFFFFF, 0xFFBDBDBD, 0xFFFFEE58).map { drawerTextIsDark(Color(it), 1f, 0f) }).containsExactly(true, true, true)
        assertThat(listOf(0xFF000000, 0xFFEF5350, 0xFFFFA726, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC).map { drawerTextIsDark(Color(it), 1f, 1f) })
            .containsExactly(false, false, false, false, false, false)
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

    /** The accent slots stay the theme's: the Personal/Work tabs and the folder editor keep their colours. */
    @Test fun theAccentsStayTheThemes() {
        val scheme = drawerColorScheme(lightTheme, Color(0xFF42A5F5), textIsDark = false)
        assertThat(scheme.primary).isEqualTo(lightTheme.primary)
        assertThat(scheme.secondaryContainer).isEqualTo(lightTheme.secondaryContainer)
        assertThat(scheme.error).isEqualTo(lightTheme.error)
    }
}
