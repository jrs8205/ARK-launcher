package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.junit.Test

/**
 * The drawer's background colour (a Telegram request after 0.9.9): the theme's scheme when none is
 * chosen; a chosen colour becomes every surface slot the drawer draws with, its text black on a light
 * colour and white on a dark one, and its chips and fields that text blended lightly into the colour.
 */
class DrawerColorSchemeTest {
    private val theme = lightColorScheme()

    @Test fun noColourKeepsTheThemesScheme() {
        assertThat(drawerColorScheme(theme, null)).isSameInstanceAs(theme)
    }

    @Test fun aLightColourTakesDarkText() {
        val yellow = Color(0xFFFFEE58)
        val scheme = drawerColorScheme(theme, yellow)

        assertThat(drawerTextIsDark(yellow)).isTrue()
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
        val navy = Color(0xFF0D1B3E)
        val scheme = drawerColorScheme(theme, navy)

        assertThat(drawerTextIsDark(navy)).isFalse()
        assertThat(scheme.surface).isEqualTo(navy)
        assertThat(scheme.onSurface).isEqualTo(Color.White)
        assertThat(scheme.surfaceVariant.luminance()).isGreaterThan(navy.luminance())
        assertThat(scheme.outline.luminance()).isGreaterThan(scheme.outlineVariant.luminance())
    }

    /** The label swatches: white, grey and yellow read dark text; black, red, orange (relative luminance 0.48),
     *  green, blue and purple light. */
    @Test fun theSwatchesSplitAsExpected() {
        assertThat(listOf(0xFFFFFFFF, 0xFFBDBDBD, 0xFFFFEE58).map { drawerTextIsDark(Color(it)) }).containsExactly(true, true, true)
        assertThat(listOf(0xFF000000, 0xFFEF5350, 0xFFFFA726, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC).map { drawerTextIsDark(Color(it)) })
            .containsExactly(false, false, false, false, false, false)
    }

    /** The search field and the folder cards draw from the expressive palette: on a chosen colour it takes the
     *  light palette's text on a light colour, the dark palette's on a dark one, and a field background tinted from
     *  the colour itself (the first build left a dark slab on a yellow drawer). */
    @Test fun theExpressivePaletteFollowsTheColour() {
        assertThat(drawerExpressivePalette(DarkExpressivePalette, null)).isSameInstanceAs(DarkExpressivePalette)

        val yellow = Color(0xFFFFEE58)
        val onYellow = drawerExpressivePalette(DarkExpressivePalette, yellow)
        assertThat(onYellow.text).isEqualTo(LightExpressivePalette.text)
        assertThat(onYellow.bg).isEqualTo(yellow)
        assertThat(onYellow.surfaceHi.luminance()).isLessThan(yellow.luminance())
        assertThat(onYellow.surfaceHi.luminance()).isGreaterThan(0.4f)

        val navy = Color(0xFF0D1B3E)
        val onNavy = drawerExpressivePalette(LightExpressivePalette, navy)
        assertThat(onNavy.text).isEqualTo(DarkExpressivePalette.text)
        assertThat(onNavy.surfaceHi.luminance()).isGreaterThan(navy.luminance())
    }

    /** The accent slots stay the theme's: the Personal/Work tabs and the folder editor keep their colours. */
    @Test fun theAccentsStayTheThemes() {
        val scheme = drawerColorScheme(theme, Color(0xFF42A5F5))
        assertThat(scheme.primary).isEqualTo(theme.primary)
        assertThat(scheme.secondaryContainer).isEqualTo(theme.secondaryContainer)
        assertThat(scheme.error).isEqualTo(theme.error)
    }
}
