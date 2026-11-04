package org.arkikeskus.launcher.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.withFontFamily
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Text given a style of its own replaces the theme's text style wholesale, so the font the user chose (the theme's
 * typography, in LocalTextStyle) falls back to the platform's. themedTextStyle merges the style on top of the theme's,
 * so the font stays and the style's own fields (size, weight, shadow, platform options) win.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ThemedTextStyleTest {
    @get:Rule val compose = createComposeRule()

    private fun styleOf(text: String): TextStyle {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().layoutInput.style
    }

    @Test fun aStyleOfItsOwnKeepsTheThemesFontWhenMerged() {
        compose.setContent {
            MaterialTheme(typography = Typography().withFontFamily(FontFamily.Serif)) {
                Column {
                    Text("merged", style = themedTextStyle(TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)))
                    Text("bare", style = TextStyle(fontSize = 20.sp))
                }
            }
        }
        compose.waitForIdle()

        val merged = styleOf("merged")
        assertThat(merged.fontFamily).isEqualTo(FontFamily.Serif)
        assertThat(merged.fontSize).isEqualTo(20.sp)
        assertThat(merged.fontWeight).isEqualTo(FontWeight.Medium)
        assertThat(styleOf("bare").fontFamily).isNull() // the trap this guards against
    }
}
