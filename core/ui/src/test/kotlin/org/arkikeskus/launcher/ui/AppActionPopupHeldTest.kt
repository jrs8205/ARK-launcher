package org.arkikeskus.launcher.ui

import android.os.Process
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * Issue #12 opens an icon's menu while the finger that lifted the icon is still down. A second finger
 * on the menu must land in the menu, not fall through to the icons behind it, and a row does nothing
 * until the holding finger lifts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en")
class AppActionPopupHeldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = AppItem("com.example", "Main", Process.myUserHandle(), 0L, "Example")
    private var held by mutableStateOf(false)
    private var picked = 0

    private fun show() {
        compose.setContent {
            AppActionPopup(
                app = app,
                anchor = IntOffset(200, 200),
                preferAbove = false,
                actions = listOf(PopupAction("App info") { picked++ }),
                onDismiss = {},
                held = held,
            )
        }
        // The card shows once its shortcut query (off the main thread) has answered.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("App info").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun popupWindow(): View = Shadow.extract<ShadowWindowManagerImpl>(compose.activity.windowManager)
        .views.single { it.javaClass.simpleName == "PopupLayout" }

    /** Android 11–12L route a second finger to the window of the first unless the one it lands on
     *  splits touches: without the flag it reached the launcher, and launched the app under the row. */
    @Suppress("DEPRECATION")
    @Test fun theMenuWindowTakesASecondFinger() {
        show()

        val flags = (popupWindow().layoutParams as WindowManager.LayoutParams).flags

        assertThat(flags and WindowManager.LayoutParams.FLAG_SPLIT_TOUCH).isNotEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).isNotEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isEqualTo(0)
    }

    @Test fun whileTheIconIsHeldARowDoesNothing() {
        held = true
        show()

        compose.onNodeWithText("App info").performClick()
        compose.waitForIdle()
        assertThat(picked).isEqualTo(0)

        held = false
        compose.waitForIdle()
        compose.onNodeWithText("App info").performClick()
        assertThat(picked).isEqualTo(1)
    }
}
