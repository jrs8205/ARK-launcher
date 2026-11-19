package org.arkikeskus.launcher.ui.component

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The height of a row of app icons, in pixels, as a list of whole rows counts it ([appRowHeightPx]). It is the
 * row's own height: the icon, the gap, the label's lines and the padding, each rounded the way the layout
 * rounds it. Counted from one height in dp it came out a pixel or two off at a density of 1.5, and a list
 * that may show three rows showed two; counted too low it would cut the last row's names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the label's lines are measured with the real font
class AppRowHeightTest {
    @get:Rule val compose = createComposeRule()

    private val app = AppItem("com.example", "Main", Process.myUserHandle(), 0L, "A calendar with a very long name indeed")

    /** A row as a folder draws it: how many lines its names may take, how large they are, whether they show. */
    private data class Row(val lines: Int, val scale: Float, val showLabel: Boolean)

    private fun assertTheCountedHeightIsTheRowsOwn() {
        var row by mutableStateOf(Row(lines = 1, scale = 1f, showLabel = true))
        var counted = 0
        compose.setContent {
            val now = row
            CompositionLocalProvider(LocalAppLabelLines provides now.lines, LocalAppLabelScale provides now.scale) {
                counted = with(LocalDensity.current) {
                    appRowHeightPx(
                        iconSize = 56.dp, showLabel = now.showLabel, labelScale = now.scale,
                        fontFactor = labelFontFactor(fontScale), lines = now.lines, verticalPadding = 10.dp,
                    )
                }
                Box(Modifier.width(82.dp)) {
                    AppIcon(
                        appItem = app, labelColor = Color.Black, showLabel = now.showLabel, reserveLabelLines = true,
                        modifier = Modifier.testTag("cell").padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
        }
        for (lines in listOf(1, 2)) for (scale in listOf(1f, 1.15f, 1.3f, 1.6f)) for (showLabel in listOf(true, false)) {
            row = Row(lines, scale, showLabel)
            compose.waitForIdle()
            val measured = compose.onNodeWithTag("cell").fetchSemanticsNode().size.height
            assertWithMessage("$row at density ${compose.density.density}").that(counted).isEqualTo(measured)
        }
    }

    @Test fun atAWholeDensity() = assertTheCountedHeightIsTheRowsOwn()

    @Config(qualifiers = "en-hdpi")
    @Test fun atADensityOfOneAndAHalf() = assertTheCountedHeightIsTheRowsOwn()

    @Config(qualifiers = "en-420dpi")
    @Test fun atADensityOfTwoAndFiveEighths() = assertTheCountedHeightIsTheRowsOwn()
}
