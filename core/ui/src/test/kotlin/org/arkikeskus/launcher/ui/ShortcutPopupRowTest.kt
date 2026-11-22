package org.arkikeskus.launcher.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** One shortcut row of the long-press popup: the shortcut's icon, then its long label when it fits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutPopupRowTest {
    @get:Rule val compose = createComposeRule()

    private fun row(label: String, shortLabel: String, icon: ImageBitmap? = null) {
        compose.setContent {
            // The popup card's width.
            Box(Modifier.width(232.dp)) {
                ShortcutPopupRow(label = label, shortLabel = shortLabel, icon = icon, onClick = {}, onPin = {})
            }
        }
    }

    private fun solidRed(): ImageBitmap = ImageBitmap(48, 48).also {
        Canvas(it).drawRect(0f, 0f, 48f, 48f, Paint().apply { color = Color.Red })
    }

    private fun screenshot(): Bitmap {
        val view = (compose as AndroidComposeTestRule<*, *>).activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            .also { view.draw(android.graphics.Canvas(it)) }
    }

    @Test fun aLongLabelThatFitsIsShown() {
        row(label = "New alarm", shortLabel = "Alarm")

        compose.onNodeWithText("New alarm").assertExists()
    }

    @Test fun aLongLabelThatDoesNotFitFallsBackToTheShortLabel() {
        row(label = "Start a new conversation with everyone", shortLabel = "New chat")

        compose.onNodeWithText("New chat").assertExists()
        compose.onNodeWithText("Start a new conversation with everyone").assertDoesNotExist()
    }

    @Test fun theShortcutsOwnIconIsDrawnBeforeItsLabel() {
        row(label = "New alarm", shortLabel = "Alarm", icon = solidRed())

        // The unmerged node: the clickable row merges the text into itself, starting at the row's edge.
        val label = compose.onNodeWithText("New alarm", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val shot = screenshot()
        val y = label.center.y.roundToInt()
        val redBeforeLabel = (0 until label.left.roundToInt()).any { x ->
            val p = shot.getPixel(x, y)
            android.graphics.Color.red(p) > 200 && android.graphics.Color.green(p) < 60 && android.graphics.Color.blue(p) < 60
        }
        assertThat(redBeforeLabel).isTrue()
    }
}
