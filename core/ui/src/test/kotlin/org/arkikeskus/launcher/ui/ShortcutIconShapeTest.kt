package org.arkikeskus.launcher.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.IconShape
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A shortcut pinned on the home screen (a contact, a web page) is drawn in the icon shape the user picked,
 * by the same rule as an app's icon: its layers cut to the shape, or a picture without layers enlarged to
 * fill it. With the system's shape it is drawn as Android hands it over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutIconShapeTest {
    private val resources = RuntimeEnvironment.getApplication().resources
    private val size = 144

    private fun adaptive(): Drawable = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.BLUE))

    private fun picture(colour: Int): Drawable =
        BitmapDrawable(resources, Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) })

    private fun drawn(icon: Drawable, shape: IconShape): Bitmap = shortcutIcon(icon, shape, size).asAndroidBitmap()

    private fun Bitmap.clear(x: Int, y: Int) = Color.alpha(getPixel(x, y)) == 0

    @Test
    fun `a shortcut with layers is cut to the picked shape`() {
        val circle = drawn(adaptive(), IconShape.CIRCLE)
        val square = drawn(adaptive(), IconShape.SQUARE)

        assertThat(circle.width).isEqualTo(size)
        assertThat(circle.getPixel(72, 72)).isEqualTo(Color.BLUE)
        assertThat(circle.clear(4, 4)).isTrue()
        assertThat(square.getPixel(8, 8)).isEqualTo(Color.BLUE)
    }

    @Test
    fun `a shortcut that is a plain picture fills the shape`() {
        // A contact's photo, a page's picture: a full tile, cut to the shape like any old icon that is one.
        val shaped = drawn(picture(Color.GREEN), IconShape.SQUARE)

        assertThat(shaped.getPixel(72, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(8, 8)).isEqualTo(Color.GREEN)
        assertThat(drawn(picture(Color.GREEN), IconShape.HEXAGON).clear(8, 8)).isTrue()
    }

    @Test
    fun `with the system's shape a shortcut is drawn as Android hands it over`() {
        val own = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        adaptive().apply { setBounds(0, 0, size, size) }.draw(Canvas(own))

        val system = drawn(adaptive(), IconShape.SYSTEM)

        for ((x, y) in listOf(0 to 0, 72 to 72, 4 to 4, 140 to 72)) {
            assertThat(system.getPixel(x, y)).isEqualTo(own.getPixel(x, y))
        }
        // And a plain picture gets no plate.
        assertThat(drawn(picture(Color.GREEN), IconShape.SYSTEM).getPixel(2, 2)).isEqualTo(Color.GREEN)
    }
}
