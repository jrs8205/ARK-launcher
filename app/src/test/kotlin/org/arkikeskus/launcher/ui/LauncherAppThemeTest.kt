package org.arkikeskus.launcher.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.BasePalette
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.designsystem.theme.chameleonSeed
import org.arkikeskus.launcher.designsystem.theme.currentBasePalette
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The theme root of every activity: nothing is drawn before the stored choice is known (so no
 * frame shows another colour), then everything is in the chosen base colour; the Chameleon starts
 * in the wallpaper colour it last followed and reports a new one when the wallpaper changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class LauncherAppThemeTest {
    @get:Rule val compose = createComposeRule()

    private val sea = 0xFF1F8EF1.toInt()
    private val forest = 0xFF3A7D44.toInt()
    private fun choice(colour: String, last: Int? = null) = ThemeChoice(LauncherSettings.FONT_DEFAULT, colour, last)

    @Test fun nothingIsComposedBeforeTheStoredChoiceIsKnown() {
        var choice: ThemeChoice? by mutableStateOf(null)
        var seen: BasePalette? = null
        compose.setContent {
            LauncherAppTheme(choice, onChameleonColour = {}, wallpaperArgb = { it }) {
                seen = currentBasePalette()
                Text("content")
            }
        }
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText("content").fetchSemanticsNodes()).isEmpty()
        assertThat(seen).isNull()

        choice = choice(LauncherSettings.BASE_COLOUR_TEAL)
        compose.onNodeWithText("content").assertExists()
        // The first palette the content ever sees is the chosen one: no frame in the default colour.
        assertThat(seen).isEqualTo(basePalette(PaletteSeed.Hue(182f), dark = false))
    }

    @Test fun theChosenColourIsThePaletteAndAnotherChoiceRecolours() {
        var choice: ThemeChoice? by mutableStateOf(choice(LauncherSettings.BASE_COLOUR_BLACK))
        var seen: BasePalette? = null
        compose.setContent {
            LauncherAppTheme(choice, onChameleonColour = {}, wallpaperArgb = { it }) { seen = currentBasePalette() }
        }
        compose.waitForIdle()
        assertThat(seen).isEqualTo(basePalette(PaletteSeed.Mono, dark = false))
        choice = choice(LauncherSettings.BASE_COLOUR_YELLOW)
        compose.waitForIdle()
        assertThat(seen).isEqualTo(basePalette(PaletteSeed.Hue(46f, 1f), dark = false))
    }

    @Test fun theChameleonStartsInTheColourItLastFollowed() {
        var seen: BasePalette? = null
        val reported = mutableListOf<Int?>()
        compose.setContent {
            LauncherAppTheme(
                choice(LauncherSettings.BASE_COLOUR_CHAMELEON, last = sea),
                onChameleonColour = { reported += it },
                wallpaperArgb = { initial -> initial },
            ) { seen = currentBasePalette() }
        }
        compose.waitForIdle()
        assertThat(seen).isEqualTo(basePalette(chameleonSeed(sea), dark = false))
        // The wallpaper still has the colour that is stored: nothing to write.
        assertThat(reported).isEmpty()
    }

    @Test fun aNewWallpaperColourRecoloursTheChameleonAndIsReportedOnce() {
        var wallpaper: Int? by mutableStateOf(sea)
        var seen: BasePalette? = null
        val reported = mutableListOf<Int?>()
        compose.setContent {
            LauncherAppTheme(
                choice(LauncherSettings.BASE_COLOUR_CHAMELEON, last = sea),
                onChameleonColour = { reported += it },
                wallpaperArgb = { wallpaper },
            ) { seen = currentBasePalette() }
        }
        compose.waitForIdle()
        wallpaper = forest
        compose.waitForIdle()
        assertThat(seen).isEqualTo(basePalette(chameleonSeed(forest), dark = false))
        assertThat(reported).containsExactly(forest)
    }

    @Test fun aFixedColourNeverAsksForTheWallpaper() {
        var asked = 0
        val reported = mutableListOf<Int?>()
        compose.setContent {
            LauncherAppTheme(
                choice(LauncherSettings.BASE_COLOUR_ORANGE, last = sea),
                onChameleonColour = { reported += it },
                wallpaperArgb = { asked++; forest },
            ) {}
        }
        compose.waitForIdle()
        assertThat(asked).isEqualTo(0)
        assertThat(reported).isEmpty()
    }
}
