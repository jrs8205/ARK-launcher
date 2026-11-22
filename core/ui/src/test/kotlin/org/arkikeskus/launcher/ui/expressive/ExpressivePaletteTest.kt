package org.arkikeskus.launcher.ui.expressive

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.designsystem.theme.toColorScheme
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hand-made palette (Settings, the drawer, the long-press menus) and the Material scheme (the
 * dialogs and pickers) are two views of the ONE base palette the theme root provides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ExpressivePaletteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theExpressivePaletteIsTheBasePalettesRoles() {
        for (dark in listOf(true, false)) {
            val base = basePalette(PaletteSeed.Hue(182f), dark)
            val p = base.toExpressive()
            assertThat(p.bg).isEqualTo(base.background)
            assertThat(p.surfaceHi).isEqualTo(base.card)
            assertThat(p.text).isEqualTo(base.text)
            assertThat(p.dim).isEqualTo(base.dim)
            assertThat(p.faint).isEqualTo(base.faint)
            assertThat(p.trackOff).isEqualTo(base.track)
            assertThat(p.thumbOff).isEqualTo(base.offThumb)
            assertThat(p.fill).isEqualTo(base.fill)
            assertThat(p.onFill).isEqualTo(base.onFill)
            assertThat(p.container).isEqualTo(base.container)
            assertThat(p.onContainer).isEqualTo(base.onContainer)
            assertThat(p.accent).isEqualTo(base.accentText)
            assertThat(p.shadow).isEqualTo(if (dark) 0.dp else 1.dp)
        }
    }

    @Test fun underTheThemeRootBothColourSystemsAreThatPalette() {
        val teal = basePalette(PaletteSeed.Hue(182f), dark = true)
        var expressive: ExpressivePalette? = null
        var material: ColorScheme? = null
        compose.setContent {
            LauncherTheme(palette = teal) {
                ExpressiveTheme {
                    expressive = LocalExpressivePalette.current
                    material = MaterialTheme.colorScheme
                }
            }
        }
        compose.waitForIdle()
        assertThat(expressive).isEqualTo(teal.toExpressive())
        assertThat(material!!.primary).isEqualTo(teal.toColorScheme().primary)
        assertThat(material!!.surfaceContainerHigh).isEqualTo(teal.toColorScheme().surfaceContainerHigh)
        assertThat(material!!.background).isEqualTo(teal.background)
    }

    @Test fun withoutAThemeRootThePaletteIsTheDefaultColourInThePhonesMode() {
        var expressive: ExpressivePalette? = null
        compose.setContent { ExpressiveTheme { expressive = LocalExpressivePalette.current } }
        compose.waitForIdle()
        assertThat(expressive).isEqualTo(basePalette(PaletteSeed.Hue(15f), dark = false).toExpressive())
    }

    @Test fun everyStoredChoiceHasItsSeed() {
        fun hue(h: Float, s: Float = 0.96f) = PaletteSeed.Hue(h, s)
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_ORANGE, null)).isEqualTo(hue(15f))
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_BLUE, null)).isEqualTo(hue(220f))
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_GREEN, null)).isEqualTo(hue(143f, 0.85f))
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_VIOLET, null)).isEqualTo(hue(275f))
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_ROSE, null)).isEqualTo(hue(335f))
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_TEAL, null)).isEqualTo(hue(182f))
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_YELLOW, null)).isEqualTo(hue(46f, 1f))
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_RED, null)).isEqualTo(hue(355f, 0.90f))
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_BLACK, 0xFF1F8EF1.toInt())).isEqualTo(PaletteSeed.Mono)
        // Every choice of the setting is covered: none falls through to the default by accident.
        assertThat(LauncherSettings.BASE_COLOURS).hasSize(11)
        // Classic is a palette of its own, whatever the wallpaper.
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_CLASSIC, 0xFF1F8EF1.toInt())).isEqualTo(PaletteSeed.Classic)
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_CLASSIC, null)).isEqualTo(PaletteSeed.Classic)
    }

    @Test fun theChameleonFollowsTheWallpaperAndAnUnknownChoiceIsTheDefault() {
        val sea = paletteSeed(LauncherSettings.BASE_COLOUR_CHAMELEON, 0xFF1F8EF1.toInt()) as PaletteSeed.Hue
        assertThat(sea.hue).isWithin(2f).of(209f)
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_CHAMELEON, null)).isEqualTo(PaletteSeed.Mono)
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_CHAMELEON, 0xFF808080.toInt())).isEqualTo(PaletteSeed.Mono)
        // A fixed choice ignores the wallpaper.
        assertThat(paletteSeed(LauncherSettings.BASE_COLOUR_TEAL, 0xFFFF0000.toInt())).isEqualTo(PaletteSeed.Hue(182f))
        assertThat(paletteSeed("ultraviolet", 0xFFFF0000.toInt())).isEqualTo(PaletteSeed.Hue(15f))
    }
}
