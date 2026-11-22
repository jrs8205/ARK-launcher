package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** A shortcut pinned on home looks disabled while its app has it disabled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinnedShortcutIconTest {
    @get:Rule val compose = createComposeRule()

    private fun solidRed(): ImageBitmap = ImageBitmap(64, 64).also {
        Canvas(it).drawRect(0f, 0f, 64f, 64f, Paint().apply { color = Color.Red })
    }

    private fun shortcut(enabled: Boolean) = PlacedShortcut(
        rowId = 1, packageName = "com.chat", shortcutId = "mia", userSerial = 0L, label = "Mia",
        icon = solidRed(), enabled = enabled, disabledMessage = null, page = 0, cellX = 0, cellY = 0,
    )

    /** The colour drawn at the centre of the shortcut's icon. */
    private fun iconCentre(enabled: Boolean): Int {
        compose.setContent { ShortcutIconContent(shortcut(enabled), showLabel = false) }
        val centre = compose.onNodeWithContentDescription("Mia").fetchSemanticsNode().boundsInWindow.center
        val view = (compose as AndroidComposeTestRule<*, *>).activity.window.decorView
        val shot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            .also { view.draw(android.graphics.Canvas(it)) }
        return shot.getPixel(centre.x.roundToInt(), centre.y.roundToInt())
    }

    @Test fun aShortcutTheAppDisabledIsDrawnPaleGrey() {
        val p = iconCentre(enabled = false)
        val (r, g, b) = Triple(android.graphics.Color.red(p), android.graphics.Color.green(p), android.graphics.Color.blue(p))

        // Launcher3's filter: red brightened halfway to white, then desaturated, is a mid-light grey.
        assertThat(abs(r - g)).isAtMost(6)
        assertThat(abs(g - b)).isAtMost(6)
        assertThat(r).isIn(130..180)
    }

    @Test fun anEnabledShortcutKeepsItsColours() {
        val p = iconCentre(enabled = true)

        assertThat(android.graphics.Color.red(p)).isGreaterThan(200)
        assertThat(android.graphics.Color.green(p)).isLessThan(60)
    }
}
