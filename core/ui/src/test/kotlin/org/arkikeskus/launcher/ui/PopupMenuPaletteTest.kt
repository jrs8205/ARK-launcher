package org.arkikeskus.launcher.ui

import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.designsystem.theme.PaletteSeed
import org.arkikeskus.launcher.designsystem.theme.basePalette
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.expressive.toExpressive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A long-press menu is its own window. Opened on the home screen it shows the theme root's colour;
 * opened in the app drawer it is handed the drawer's palette (the user's own drawer colour), so the
 * menu and the folder card beside it agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class PopupMenuPaletteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tealBase = basePalette(PaletteSeed.Hue(182f), dark = true)
    private val violet = basePalette(PaletteSeed.Hue(275f), dark = false).toExpressive()
    private val app = AppItem("com.example", "Main", Process.myUserHandle(), 0L, "Example")
    private val anchor = IntOffset(200, 200)

    private fun colourOf(label: String): Color {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodesWithText(label, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().layoutInput.style.color
    }

    @Test fun anIconMenuOnTheHomeScreenShowsTheThemeRootsColour() {
        compose.setContent {
            LauncherTheme(palette = tealBase) {
                IconMenuPopup(anchor, preferAbove = false, items = listOf(IconMenuItem(LauncherIcons.Info, "Wallpaper") {}), onDismiss = {})
            }
        }
        assertThat(colourOf("Wallpaper")).isEqualTo(tealBase.onContainer)
    }

    @Test fun anIconMenuHandedAPaletteShowsThatOne() {
        compose.setContent {
            LauncherTheme(palette = tealBase) {
                IconMenuPopup(
                    anchor, preferAbove = false, items = listOf(IconMenuItem(LauncherIcons.Info, "Wallpaper") {}),
                    onDismiss = {}, palette = violet,
                )
            }
        }
        assertThat(colourOf("Wallpaper")).isEqualTo(violet.onContainer)
        assertThat(violet.onContainer).isNotEqualTo(tealBase.onContainer)
    }

    @Test fun anAppMenuOnTheHomeScreenShowsTheThemeRootsColour() {
        compose.setContent {
            LauncherTheme(palette = tealBase) {
                AppActionPopup(app, anchor, preferAbove = false, actions = listOf(PopupAction("App info") {}), onDismiss = {})
            }
        }
        assertThat(colourOf("App info")).isEqualTo(tealBase.onContainer)
    }

    @Test fun anAppMenuHandedAPaletteShowsThatOne() {
        compose.setContent {
            LauncherTheme(palette = tealBase) {
                AppActionPopup(
                    app, anchor, preferAbove = false, actions = listOf(PopupAction("App info") {}), onDismiss = {},
                    palette = violet,
                )
            }
        }
        assertThat(colourOf("App info")).isEqualTo(violet.onContainer)
    }
}
