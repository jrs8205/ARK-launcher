package org.arkikeskus.launcher.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.ui.expressive.DarkExpressivePalette
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LightExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The drawer's folder card read its text colours from the drawer's palette and then opened the popup, which
 * re-applied the SYSTEM palette inside: light text on a light card (1.1:1) on a dark drawer colour under a light
 * system theme. A palette handed to the popup is the one its card and its content see; none, the system's as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class AnchoredPopupCardPaletteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aPaletteHandedToThePopupIsTheOneItsContentSees() {
        var seen: ExpressivePalette? = null
        compose.setContent {
            AnchoredPopupCard(anchor = IntOffset(200, 200), preferAbove = false, onDismiss = {}, palette = DarkExpressivePalette) {
                seen = LocalExpressivePalette.current
                Text("inside")
            }
        }
        compose.onNodeWithText("inside").assertIsDisplayed()
        assertThat(seen).isSameInstanceAs(DarkExpressivePalette)
    }

    @Test fun withoutAPaletteThePopupKeepsTheSystems() {
        var seen: ExpressivePalette? = null
        compose.setContent {
            CompositionLocalProvider(LocalExpressivePalette provides DarkExpressivePalette) {
                AnchoredPopupCard(anchor = IntOffset(200, 200), preferAbove = false, onDismiss = {}) {
                    seen = LocalExpressivePalette.current
                    Text("inside")
                }
            }
        }
        compose.onNodeWithText("inside").assertIsDisplayed()
        assertThat(seen).isSameInstanceAs(LightExpressivePalette)
    }
}
