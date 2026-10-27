package org.arkikeskus.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The menu's caret points at the icon: its offset is measured from the card's LEFT edge, so under a
 * right-to-left system language (the drawer is not locked to LTR) it must not mirror to the other
 * side of the card, away from the icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class PopupCaretDirectionTest {
    @get:Rule val compose = createComposeRule()

    private val direction = mutableStateOf(LayoutDirection.Ltr)

    private fun caretLeft(): Float {
        compose.waitForIdle()
        return compose.onNodeWithTag("popup-caret").fetchSemanticsNode().positionInRoot.x
    }

    @Test fun theCaretStaysAtTheIconUnderRtl() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                PopupCardContent(
                    anchor = IntOffset(40, 300), // near the left edge: the card is clamped, the caret is not centred
                    preferAbove = false,
                    windowWidthPx = 1080f,
                ) { Text("App info") }
            }
        }
        val ltr = caretLeft()

        direction.value = LayoutDirection.Rtl
        val rtl = caretLeft()

        assertThat(ltr).isGreaterThan(0f)
        assertThat(rtl).isWithin(1f).of(ltr)
    }
}
