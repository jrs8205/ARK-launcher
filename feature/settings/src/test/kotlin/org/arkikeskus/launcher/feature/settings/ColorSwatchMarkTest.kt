package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.designsystem.theme.DefaultPaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.paletteSeed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chosen colour swatch must be told from the others whatever the base colour is. Its ring is the theme's fill,
 * and a fill can be the swatch's own colour (the Black look's fill on the black swatch, the dark Black look's on the
 * white one, a blue fill on the blue one), so the swatch also carries a check in the black or white that reads on
 * the swatch itself: a state that can be told at 3:1 on every swatch, in every base colour, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorSwatchMarkTest {
    @get:Rule val compose = createComposeRule()

    /**
     * How many pixels in the middle of swatch [index] of a row tell themselves from the swatch's own colour at 3:1.
     * The middle is the square a quarter of the swatch in from each side: inside the ring all the way round.
     */
    private fun marked(row: PixelMap, index: Int): Int {
        val cell = row.width / COLOR_SWATCHES.size
        val swatch = Color(COLOR_SWATCHES[index]).luminance()
        var count = 0
        for (x in cell / 4 until cell - cell / 4) for (y in cell / 4 until cell - cell / 4) {
            if (contrastRatio(row[index * cell + x, y].luminance(), swatch) >= 3f) count++
        }
        return count
    }

    @Test fun theMarkOnASwatchReadsOnTheSwatchItself() {
        for (argb in COLOR_SWATCHES + listOf(0xFF141414, 0xFF777777, 0xFF808080, 0xFFF5F5F5).map { it.toInt() }) {
            val swatch = Color(argb)
            assertWithMessage("the mark on #%06X".format(argb and 0xFFFFFF))
                .that(contrastRatio(swatchMarkColor(swatch).luminance(), swatch.luminance())).isAtLeast(4.5f)
        }
    }

    @Test fun theChosenSwatchIsToldFromTheOthersInEveryBaseColour() {
        var look by mutableStateOf(basePalette(DefaultPaletteSeed, dark = false))
        compose.setContent {
            val palette = settingsPalette(look)
            CompositionLocalProvider(LocalExpressivePalette provides palette) {
                Column(Modifier.background(palette.surfaceHi)) {
                    Row(Modifier.testTag("chosen")) { COLOR_SWATCHES.forEach { ColorSwatch(argb = it, selected = true) {} } }
                    Row(Modifier.testTag("others")) { COLOR_SWATCHES.forEach { ColorSwatch(argb = it, selected = false) {} } }
                }
            }
        }
        // The Chameleon without a wallpaper colour is the Black look; its hues are the Chameleon's own business.
        for (colour in LauncherSettings.BASE_COLOURS) for (dark in listOf(false, true)) {
            look = basePalette(paletteSeed(colour, null), dark)
            compose.waitForIdle()
            val chosen = compose.onNodeWithTag("chosen").captureToImage().toPixelMap()
            val others = compose.onNodeWithTag("others").captureToImage().toPixelMap()
            for (index in COLOR_SWATCHES.indices) {
                val where = "#%06X in $colour, dark=$dark".format(COLOR_SWATCHES[index] and 0xFFFFFF)
                assertWithMessage("the chosen swatch $where is marked").that(marked(chosen, index)).isAtLeast(8)
                assertWithMessage("the swatch $where that is not chosen carries no mark").that(marked(others, index)).isEqualTo(0)
            }
        }
    }
}
