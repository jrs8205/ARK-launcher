package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PageDotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ringMarkerDrawsEveryPageAsADot() {
        compose.setContent { PageDots(count = 3, current = 0, home = 1, homeMarker = LauncherSettings.MARKER_RING) }
        compose.onAllNodesWithTag(PAGE_DOT_TAG).assertCountEquals(3)
        compose.onNodeWithTag(PAGE_DOT_HOUSE_TAG).assertDoesNotExist()
    }

    @Test fun houseMarkerReplacesOnlyTheHomePageDot() {
        compose.setContent { PageDots(count = 3, current = 0, home = 1, homeMarker = LauncherSettings.MARKER_HOUSE) }
        compose.onAllNodesWithTag(PAGE_DOT_TAG).assertCountEquals(2)
        compose.onAllNodesWithTag(PAGE_DOT_HOUSE_TAG).assertCountEquals(1)
    }

    @Test fun houseMarkerSitsAtTheHomePageIndex() {
        compose.setContent { PageDots(count = 3, current = 0, home = 1, homeMarker = LauncherSettings.MARKER_HOUSE) }
        // Left to right: page 0's dot, the house (page 1), page 2's dot.
        val dots = compose.onAllNodesWithTag(PAGE_DOT_TAG)
        val first = dots[0].getUnclippedBoundsInRoot()
        val house = compose.onNodeWithTag(PAGE_DOT_HOUSE_TAG).getUnclippedBoundsInRoot()
        val last = dots[1].getUnclippedBoundsInRoot()
        assertThat(first.right.value).isLessThan(house.left.value)
        assertThat(house.right.value).isLessThan(last.left.value)
    }

    /** The dots on a white wallpaper: what a pixel just outside a marker looks like. */
    private fun luminanceBeside(homeMarker: String, tag: String, index: Int): Float {
        compose.setContent {
            Box(Modifier.background(Color.White).padding(24.dp)) {
                PageDots(count = 3, current = 0, home = 1, homeMarker = homeMarker)
            }
        }
        compose.waitForIdle()
        val bounds = compose.onAllNodesWithTag(tag)[index].fetchSemanticsNode().boundsInWindow
        // captureToImage waits for a frame Robolectric never draws: draw the window ourselves.
        val window = (compose as AndroidComposeTestRule<*, *>).activity.window.decorView
        val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { window.draw(Canvas(bitmap)) }
        val x = (bounds.right + with(compose.density) { 2.dp.toPx() }).roundToInt()
        val y = bounds.center.y.roundToInt()
        return Color(bitmap.getPixel(x, y)).luminance()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onAWhiteWallpaperADotHasADarkerRingAroundIt() {
        // The last dot: inactive, the faintest of them.
        assertThat(luminanceBeside(LauncherSettings.MARKER_RING, PAGE_DOT_TAG, index = 1)).isLessThan(0.9f)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onAWhiteWallpaperTheHouseHasADarkerRingAroundIt() {
        assertThat(luminanceBeside(LauncherSettings.MARKER_HOUSE, PAGE_DOT_HOUSE_TAG, index = 0)).isLessThan(0.9f)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun automaticDarkInkReachesTheDrawnPageDot() {
        compose.setContent {
            Box(Modifier.background(Color.White).padding(24.dp)) {
                PageDots(count = 3, current = 0, home = 1, homeMarker = LauncherSettings.MARKER_RING, ink = Color.Black)
            }
        }
        val bounds = compose.onAllNodesWithTag(PAGE_DOT_TAG)[0].fetchSemanticsNode().boundsInWindow
        val window = (compose as AndroidComposeTestRule<*, *>).activity.window.decorView
        val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { window.draw(Canvas(bitmap)) }
        assertThat(Color(bitmap.getPixel(bounds.center.x.roundToInt(), bounds.center.y.roundToInt()))).isEqualTo(Color.Black)
    }

}
