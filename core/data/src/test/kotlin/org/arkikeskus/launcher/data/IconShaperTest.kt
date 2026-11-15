package org.arkikeskus.launcher.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import coil3.request.Options
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.CustomIconImage
import org.arkikeskus.launcher.model.IconRequest
import org.arkikeskus.launcher.model.IconShape
import org.arkikeskus.launcher.model.shapedIcon
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An icon drawn in a shape the user picked (issue #26): what is cut away, what an old icon stands on, what is left alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconShaperTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val size = 144

    /** A layer that paints [colour] on the middle third of its bounds and nothing elsewhere. */
    private class MiddleThird(private val colour: Int) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(
                b.left + b.width() / 3f, b.top + b.height() / 3f, b.right - b.width() / 3f, b.bottom - b.height() / 3f,
                Paint().apply { color = colour },
            )
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun solid(colour: Int, side: Int = 48): Drawable =
        BitmapDrawable(context.resources, Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) })

    private fun Bitmap.clear(x: Int, y: Int) = Color.alpha(getPixel(x, y)) == 0

    // --- An adaptive icon: its two layers, cut by the shape instead of the phone's mask ----------------

    @Test
    fun `an adaptive icon fills the shape and is cut where the shape ends`() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.BLUE))

        val circle = shapedIcon(icon, IconShape.CIRCLE, size)

        assertThat(circle.width).isEqualTo(size)
        assertThat(circle.height).isEqualTo(size)
        assertThat(circle.getPixel(72, 72)).isEqualTo(Color.BLUE)
        assertThat(circle.getPixel(72, 3)).isEqualTo(Color.BLUE)
        assertThat(circle.clear(4, 4)).isTrue()
        assertThat(circle.clear(140, 140)).isTrue()
    }

    @Test
    fun `a square keeps the corners a circle cuts away`() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), null)

        assertThat(shapedIcon(icon, IconShape.SQUARE, size).getPixel(8, 8)).isEqualTo(Color.RED)
        assertThat(shapedIcon(icon, IconShape.CIRCLE, size).clear(8, 8)).isTrue()
    }

    @Test
    fun `the layers are drawn at their own size, half as large again as the icon`() {
        // An adaptive layer is 108 dp for the 72 dp that shows. Its middle third is then half of what shows:
        // from 36 to 108 of 144. Drawn only as large as the icon it would end at 48 and 96.
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), MiddleThird(Color.GREEN))

        val shaped = shapedIcon(icon, IconShape.SQUARE, size)

        assertThat(shaped.getPixel(42, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(102, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(30, 72)).isEqualTo(Color.RED)
        assertThat(shaped.getPixel(72, 114)).isEqualTo(Color.RED)
    }

    @Test
    fun `shaping leaves the icon's own layers where they were`() {
        val foreground = ColorDrawable(Color.BLUE).apply { setBounds(1, 2, 3, 4) }
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), foreground)
        val before = icon.foreground.copyBounds()

        shapedIcon(icon, IconShape.CIRCLE, size)

        assertThat(icon.foreground.copyBounds()).isEqualTo(before)
    }

    // --- Under the layers: black, as Android itself draws an adaptive icon ------------------------------

    @Test
    fun `a see-through background shows black, not the wallpaper`() {
        // AdaptiveIconDrawable fills its layers' canvas with black before it draws them.
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.TRANSPARENT), MiddleThird(Color.GREEN))

        val shaped = shapedIcon(icon, IconShape.SQUARE, size)

        assertThat(shaped.getPixel(20, 72)).isEqualTo(Color.BLACK)
        assertThat(shaped.getPixel(72, 72)).isEqualTo(Color.GREEN)
    }

    @Test
    fun `a half see-through background is laid over black and comes out opaque`() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.argb(128, 255, 0, 0)), null)

        val pixel = shapedIcon(icon, IconShape.SQUARE, size).getPixel(72, 72)

        assertThat(Color.alpha(pixel)).isEqualTo(255)
        assertThat(Color.red(pixel)).isIn(126..130)
        assertThat(Color.green(pixel)).isEqualTo(0)
        assertThat(Color.blue(pixel)).isEqualTo(0)
    }

    @Test
    fun `an icon with no background layer at all stands on black too`() {
        val shaped = shapedIcon(AdaptiveIconDrawable(null, MiddleThird(Color.GREEN)), IconShape.CIRCLE, size)

        assertThat(shaped.getPixel(72, 12)).isEqualTo(Color.BLACK)
        // The black is the icon's own floor: outside the shape there is still nothing.
        assertThat(shaped.clear(4, 4)).isTrue()
    }

    @Test
    fun `a shaped icon has the colours Android gives the same icon`() {
        // The same layers, once through the launcher's square and once through Android's own drawing:
        // in the middle, where every mask is open, the two must agree.
        val layers = { AdaptiveIconDrawable(ColorDrawable(Color.argb(96, 0, 0, 255)), MiddleThird(Color.argb(160, 255, 255, 0))) }
        val own = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        layers().apply { setBounds(0, 0, size, size) }.draw(Canvas(own))

        val shaped = shapedIcon(layers(), IconShape.SQUARE, size)

        for ((x, y) in listOf(72 to 72, 72 to 30, 30 to 72)) {
            assertThat(shaped.getPixel(x, y)).isEqualTo(own.getPixel(x, y))
        }
    }

    // --- An icon that is not adaptive: smaller, on a white plate of the shape -------------------------

    @Test
    fun `an old icon stands smaller on a white plate of the shape`() {
        val shaped = shapedIcon(solid(Color.GREEN), IconShape.CIRCLE, size)

        // The icon itself, in the middle; 0.64 of 144 is 92: from 26 to 118.
        assertThat(shaped.getPixel(72, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(30, 72)).isEqualTo(Color.GREEN)
        // The plate around it, inside the circle.
        assertThat(shaped.getPixel(72, 10)).isEqualTo(Color.WHITE)
        assertThat(shaped.getPixel(18, 72)).isEqualTo(Color.WHITE)
        // Nothing outside the circle.
        assertThat(shaped.clear(4, 4)).isTrue()
    }

    @Test
    fun `the plate has the picked shape`() {
        assertThat(shapedIcon(solid(Color.GREEN), IconShape.SQUARE, size).getPixel(8, 8)).isEqualTo(Color.WHITE)
        assertThat(shapedIcon(solid(Color.GREEN), IconShape.HEXAGON, size).clear(8, 8)).isTrue()
    }

    // --- The system's shape: nothing is redrawn --------------------------------------------------------

    @Test
    fun `with the system's shape the icon is handed on as it is`() {
        val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.RED), null)
        val old = solid(Color.GREEN)

        assertThat(ownIconInShape(adaptive, IconShape.SYSTEM, context.resources)).isSameInstanceAs(adaptive)
        assertThat(ownIconInShape(old, IconShape.SYSTEM, context.resources)).isSameInstanceAs(old)
    }

    @Test
    fun `with a picked shape the icon is a picture already cut to it`() {
        val shaped = ownIconInShape(AdaptiveIconDrawable(ColorDrawable(Color.RED), null), IconShape.CIRCLE, context.resources)

        assertThat(shaped).isInstanceOf(BitmapDrawable::class.java)
        assertThat((shaped as BitmapDrawable).bitmap.clear(2, 2)).isTrue()
    }

    // --- A gallery picture picked as an icon -----------------------------------------------------------

    private val source = CustomIconSource(context, IconPackRepository(context))

    private fun savedPicture(colour: Int): CustomIcon.FromFile =
        source.files.save(Bitmap.createBitmap(CustomIconFiles.SIZE, CustomIconFiles.SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) })

    private fun Drawable.pixels(): Bitmap = (this as BitmapDrawable).bitmap

    @Test
    fun `a gallery picture is cut to the picked shape when it is drawn`() {
        val icon = savedPicture(Color.MAGENTA)

        val circle = source.drawable(icon, IconShape.CIRCLE)!!.pixels()
        val square = source.drawable(icon, IconShape.SQUARE)!!.pixels()

        val last = circle.width - 1
        assertThat(circle.getPixel(circle.width / 2, circle.height / 2)).isEqualTo(Color.MAGENTA)
        assertThat(circle.clear(3, 3)).isTrue()
        assertThat(circle.clear(last - 3, last - 3)).isTrue()
        assertThat(square.getPixel(9, 9)).isEqualTo(Color.MAGENTA)
    }

    @Test
    fun `with the system's shape a gallery picture is cut to the phone's own`() {
        val icon = savedPicture(Color.MAGENTA)

        val drawn = source.drawable(icon, IconShape.SYSTEM)!!.pixels()

        // Whatever the phone's shape is, it has a middle and it does not reach the very corner.
        assertThat(drawn.getPixel(drawn.width / 2, drawn.height / 2)).isEqualTo(Color.MAGENTA)
        assertThat(drawn.clear(0, 0)).isTrue()
    }

    @Test
    fun `an icon picked from an icon pack is asked for as it is, whatever the shape`() {
        // No such pack here: the answer is none for every shape, and nothing is drawn in its place.
        val fromPack = CustomIcon.FromPack("com.example.gone", "mail")

        assertThat(source.drawable(fromPack, IconShape.HEXAGON)).isNull()
    }

    // --- The shape is part of what the image loader remembers an icon by -------------------------------

    private val app = AppItem("com.mail", "mail.Main", Process.myUserHandle(), 0L, "Mail")
    private val options = Options(context)

    @Test
    fun `the shape is part of an app icon's cache key`() {
        val keyer = AppIconKeyer()
        val keys = IconShape.entries.map { keyer.key(IconRequest(app, shape = it), options) }

        assertThat(keys.toSet()).hasSize(IconShape.entries.size)
        assertThat(keyer.key(IconRequest(app), options)).isEqualTo(keyer.key(IconRequest(app, shape = IconShape.SYSTEM), options))
        // Every key of the app still begins the same: forgetting an app's icons finds them all.
        assertThat(keys.all { it.startsWith(appIconKeyPrefix(app.key)) }).isTrue()
    }

    @Test
    fun `the shape is part of a picked icon's cache key`() {
        val keyer = CustomIconKeyer()
        val picture = CustomIcon.FromFile("a.png")

        assertThat(keyer.key(CustomIconImage(picture, shape = IconShape.CIRCLE), options))
            .isNotEqualTo(keyer.key(CustomIconImage(picture, shape = IconShape.SQUARE), options))
    }
}
