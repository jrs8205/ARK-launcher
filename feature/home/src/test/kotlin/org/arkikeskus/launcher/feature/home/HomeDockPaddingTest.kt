package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.graphics.Insets
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issue #24: the dock sits right above the navigation bar, where a thumb does not reach on a tall
 * phone. "Space below the dock" lifts it by the chosen amount, on top of the navigation-bar inset
 * and the dock's own outer padding; zero keeps the dock where it has always been.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeDockPaddingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val density get() = Density(RuntimeEnvironment.getApplication())

    @Test fun withoutSpacingTheDockKeepsTheNavigationBarInsetAndItsOuterPadding() {
        assertThat(bottomGap(bottomSpacing = 0.dp, navigationBarPx = 100))
            .isWithin(1f).of(100f + with(density) { DOCK_OUTER_PADDING.toPx() })
    }

    @Test fun theSpacingLiftsTheDockByThatMuchMore() {
        assertThat(bottomGap(bottomSpacing = 24.dp, navigationBarPx = 100))
            .isWithin(1f).of(100f + with(density) { (DOCK_OUTER_PADDING + 24.dp).toPx() })
    }

    /** The gap in px between the bottom of the padded dock content and the bottom of the root. */
    private fun bottomGap(bottomSpacing: Dp, navigationBarPx: Int): Float {
        compose.setContent {
            Box(Modifier.fillMaxSize().homeDockPadding(bottomSpacing = bottomSpacing)) {
                Box(Modifier.fillMaxSize().testTag("dock"))
            }
        }
        compose.runOnIdle {
            val composeView = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val insets = WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navigationBarPx))
                .setVisible(WindowInsets.Type.navigationBars(), true)
                .build()
            composeView.dispatchApplyWindowInsets(insets)
        }
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
        return root.bottom - dock.bottom
    }
}
