package org.arkikeskus.launcher.ui.expressive

import android.app.Application
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SwitchColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.ui.component.ContactAvatar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Filled controls take the palette's fill, and what is drawn on a fill takes the palette's colour
 * for that: never a fixed white. Yellow is the proof: its fill is light, so a white thumb or a
 * white label on it would be 1.4:1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ExpressiveFillsTest {
    @get:Rule val compose = createComposeRule()

    private val yellowBase = basePalette(PaletteSeed.Hue(46f, 1f), dark = true)
    private val yellow = yellowBase.toExpressive()

    @Test fun onYellowTheContentOfAFillIsDark() {
        // The premise of every test below.
        assertThat(yellow.onFill).isNotEqualTo(Color.White)
    }

    @Test fun aSwitchThatIsOnIsTheFillWithItsOwnContentColourOnIt() {
        var colors: SwitchColors? = null
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides yellow) { colors = expressiveSwitchColors() }
        }
        compose.waitForIdle()
        assertThat(colors!!.checkedTrackColor).isEqualTo(yellow.fill)
        assertThat(colors!!.checkedBorderColor).isEqualTo(yellow.fill)
        assertThat(colors!!.checkedThumbColor).isEqualTo(yellow.onFill)
        assertThat(colors!!.uncheckedTrackColor).isEqualTo(yellow.trackOff)
        assertThat(colors!!.uncheckedBorderColor).isEqualTo(yellow.trackOff)
        assertThat(colors!!.uncheckedThumbColor).isEqualTo(yellow.thumbOff)
    }

    @Test fun aFilledButtonIsTheFillWithItsOwnContentColourOnIt() {
        var colors: ButtonColors? = null
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides yellow) { colors = expressiveButtonColors() }
        }
        compose.waitForIdle()
        assertThat(colors!!.containerColor).isEqualTo(yellow.fill)
        assertThat(colors!!.contentColor).isEqualTo(yellow.onFill)
        assertThat(colors!!.disabledContainerColor).isEqualTo(yellow.fill.copy(alpha = 0.5f))
        assertThat(colors!!.disabledContentColor).isEqualTo(yellow.onFill.copy(alpha = 0.7f))
    }

    @Test fun aSliderIsDrawnInTheFill() {
        var colors: SliderColors? = null
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides yellow) { colors = expressiveSliderColors() }
        }
        compose.waitForIdle()
        assertThat(colors!!.thumbColor).isEqualTo(yellow.fill)
        assertThat(colors!!.activeTrackColor).isEqualTo(yellow.fill)
        // The step ticks take their track's colour on both sides of the thumb: they are not shown.
        assertThat(colors!!.activeTickColor).isEqualTo(yellow.fill)
        assertThat(colors!!.inactiveTickColor).isEqualTo(yellow.trackOff)
        assertThat(colors!!.inactiveTrackColor).isEqualTo(yellow.trackOff)
    }

    @Test fun aContactWithoutAPhotoHasItsInitialInTheFillsContentColour() {
        // The avatar is on the home screen too, where no expressive palette is provided: it reads the theme root's.
        compose.setContent { LauncherTheme(palette = yellowBase) { ContactAvatar(name = "Aino", photoUri = null) } }
        val node = compose.onAllNodesWithText("A", useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertThat(results.single().layoutInput.style.color).isEqualTo(yellowBase.onFill)
    }
}
