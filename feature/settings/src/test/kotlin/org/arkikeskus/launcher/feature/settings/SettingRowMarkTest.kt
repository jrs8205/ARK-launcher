package org.arkikeskus.launcher.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.ui.READABLE_TEXT
import org.arkikeskus.launcher.ui.contrastRatio
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The mark on a row a search result led to must not cost the row its legibility: it is drawn around the row's card,
 * never over what is written on it, so the card stays the card's colour under the text while the mark is at its
 * strongest, in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingRowMarkTest {
    @get:Rule val compose = createComposeRule()

    private fun near(a: Color, b: Color) =
        abs(a.red - b.red) < 0.02f && abs(a.green - b.green) < 0.02f && abs(a.blue - b.blue) < 0.02f

    private fun theMarkLeavesTheCardUnderTheTextAlone(dark: Boolean) {
        val palette = settingsPalette(dark)
        val jumps = SettingJumps().apply { target = "G4" }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides palette, LocalSettingJumps provides jumps) {
                Column(Modifier.width(320.dp)) {
                    SettingRow("G4") { ExpressiveActionRow(label = "Left edge", description = "None") {} }
                }
            }
        }
        // Inside the mark's hold: it is at its strongest.
        compose.mainClock.advanceTimeBy(120)
        assertThat(jumps.target).isEqualTo("G4")

        val pixels = compose.onNodeWithTag(settingTag("G4")).captureToImage().toPixelMap()
        val middle = (pixels.height - 10) / 2 // the frame's last 10 px are the gap under the card
        // Well inside the card, left of where the text starts (the card pads its content by 20).
        val inside = pixels[10, middle]
        assertWithMessage("the card under the text keeps its colour (was $inside)").that(near(inside, palette.surfaceHi)).isTrue()
        // So what is written on the row reads as it does without the mark.
        assertThat(contrastRatio(palette.accent.luminance(), inside.luminance())).isAtLeast(READABLE_TEXT)
        assertThat(contrastRatio(palette.dim.luminance(), inside.luminance())).isAtLeast(READABLE_TEXT)
        // And the mark is there: the card's edge wears it.
        val edge = pixels[1, middle]
        assertWithMessage("the card's edge wears the mark (was $edge)").that(near(edge, palette.surfaceHi)).isFalse()
        assertThat(contrastRatio(edge.luminance(), palette.bg.luminance())).isAtLeast(3f)
    }

    @Test fun onTheLightTheme() = theMarkLeavesTheCardUnderTheTextAlone(dark = false)

    @Test fun onTheDarkTheme() = theMarkLeavesTheCardUnderTheTextAlone(dark = true)
}
