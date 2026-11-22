package org.arkikeskus.launcher.feature.appdrawer

import android.app.Application
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search field's text selection takes the field's own colours. A text field hands its selection colours to its
 * text through a composition local, and the hint is composed in the same place: what a probe sees there is what the
 * field's text gets. The field used to set none, so its text had the theme's whatever the field looked like.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class DrawerSearchFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theFieldsTextSelectionTakesTheFieldsOwnColours() {
        val colors = SearchFieldColors(
            container = Color.DarkGray,
            content = Color.White,
            selectionHandle = Color(0xFF123456),
            selectionBackground = Color(0x66ABCDEF),
        )
        var seen: TextSelectionColors? = null
        compose.setContent {
            MaterialTheme {
                DrawerSearchField(
                    query = "",
                    onQueryChange = {},
                    colors = colors,
                    placeholder = { seen = LocalTextSelectionColors.current },
                )
            }
        }
        compose.waitForIdle()

        assertThat(seen?.handleColor).isEqualTo(colors.selectionHandle)
        assertThat(seen?.backgroundColor).isEqualTo(colors.selectionBackground)
    }
}
