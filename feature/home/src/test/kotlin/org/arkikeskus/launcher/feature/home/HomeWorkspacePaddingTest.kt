package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.graphics.Insets
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import androidx.activity.ComponentActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Without the dock the workspace is the last thing in the home column, so it must keep itself
 * above the navigation bar the way the dock does (Telegram report, 2026-10-29: with the dock off
 * the bottom row of icons sat on the navigation bar). With the dock on, the dock carries the
 * navigation-bar inset and the workspace keeps only its own 12 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeWorkspacePaddingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val density get() = Density(RuntimeEnvironment.getApplication())

    @Test fun withoutTheDockTheWorkspaceStaysAboveTheNavigationBar() {
        assertThat(bottomGap(dockShown = false, navigationBarPx = 100)).isWithin(1f).of(100f + with(density) { 12.dp.toPx() })
    }

    @Test fun withTheDockTheWorkspaceKeepsOnlyItsOwnPadding() {
        assertThat(bottomGap(dockShown = true, navigationBarPx = 100)).isWithin(1f).of(with(density) { 12.dp.toPx() })
    }

    /** The gap in px between the bottom of the padded workspace content and the bottom of the root. */
    private fun bottomGap(dockShown: Boolean, navigationBarPx: Int): Float {
        compose.setContent {
            Box(Modifier.fillMaxSize().homeWorkspacePadding(showStatusBar = true, dockShown = dockShown)) {
                Box(Modifier.fillMaxSize().testTag("content"))
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
        val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        return root.bottom - content.bottom
    }
}
