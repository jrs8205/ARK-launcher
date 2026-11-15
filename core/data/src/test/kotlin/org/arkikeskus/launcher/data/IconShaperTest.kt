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

    // --- An icon that is not adaptive (issue #26, the requester's follow-up) ---------------------------
    // One that is a full tile of its own is enlarged to fill the shape, on its own edge colour: it must not
    // sit small on a white plate. Only a free-form logo, which cannot fill a shape, keeps a plate.

    /** A picture painted in a box of 100 by 100 units, stretched over the drawable's bounds. */
    private class Painted(private val paint: Canvas.(Paint) -> Unit) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(b.width() / 100f, b.height() / 100f)
            canvas.paint(Paint(Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** A tile: a filled box from [from] to [to] of the picture's 100 units, its corners rounded by [radius]. */
    private fun tile(colour: Int, from: Float = 0f, to: Float = 100f, radius: Float = 0f): Drawable = Painted { p ->
        p.color = colour
        drawRoundRect(from, from, to, to, radius, radius, p)
    }

    /** A free-form logo: a cross whose arms span [from] to [to] and are a quarter of that thick. */
    private fun cross(colour: Int, from: Float = 0f, to: Float = 100f): Drawable = Painted { p ->
        p.color = colour
        val arm = (to - from) / 8f
        val mid = (from + to) / 2f
        drawRect(mid - arm, from, mid + arm, to, p)
        drawRect(from, mid - arm, to, mid + arm, p)
    }

    @Test
    fun `an old icon that is a full tile fills the shape`() {
        val square = shapedIcon(tile(Color.GREEN), IconShape.SQUARE, size)
        val circle = shapedIcon(tile(Color.GREEN), IconShape.CIRCLE, size)

        // Edge to edge: no plate shows anywhere.
        assertThat(square.getPixel(72, 72)).isEqualTo(Color.GREEN)
        assertThat(square.getPixel(8, 8)).isEqualTo(Color.GREEN)
        assertThat(square.getPixel(72, 10)).isEqualTo(Color.GREEN)
        assertThat(circle.getPixel(72, 3)).isEqualTo(Color.GREEN)
        assertThat(circle.clear(4, 4)).isTrue()
    }

    @Test
    fun `a tile drawn with empty margins is enlarged until it fills the shape`() {
        // Many old icons leave room around their tile: here it covers only the middle half of its picture.
        val shaped = shapedIcon(tile(Color.GREEN, from = 25f, to = 75f), IconShape.SQUARE, size)

        assertThat(shaped.getPixel(20, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(124, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(72, 20)).isEqualTo(Color.GREEN)
    }

    @Test
    fun `what a rounded tile leaves of the shape is its own colour, not white`() {
        // The tile's corners are rounder than the square's: the square shows behind them.
        val shaped = shapedIcon(tile(Color.GREEN, radius = 30f), IconShape.SQUARE, size)

        assertThat(shaped.getPixel(5, 5)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(138, 138)).isEqualTo(Color.GREEN)
    }

    @Test
    fun `a round old icon in a square shape stands on its own colour`() {
        val disc = Painted { p ->
            p.color = Color.BLUE
            drawCircle(50f, 50f, 50f, p)
        }

        val shaped = shapedIcon(disc, IconShape.SQUARE, size)

        assertThat(shaped.getPixel(72, 72)).isEqualTo(Color.BLUE)
        assertThat(shaped.getPixel(8, 8)).isEqualTo(Color.BLUE)
    }

    @Test
    fun `a faint shadow under a tile is not taken for a part of it`() {
        // A red tile over the upper four fifths of the picture, and under it a soft shadow.
        val withShadow = Painted { p ->
            p.color = Color.RED
            drawRect(0f, 0f, 100f, 80f, p)
            p.color = Color.argb(50, 0, 0, 0)
            drawRect(0f, 80f, 100f, 100f, p)
        }

        val shaped = shapedIcon(withShadow, IconShape.SQUARE, size)

        // The tile alone is what is centred in the shape: its lower part is where the shadow would be
        // had the shadow counted (the tile would then start at the top and end at 115 of 144).
        assertThat(shaped.getPixel(72, 122)).isEqualTo(Color.RED)
        assertThat(shaped.getPixel(72, 72)).isEqualTo(Color.RED)
        // Above the tile, the shape is the tile's own colour.
        assertThat(shaped.getPixel(72, 5)).isEqualTo(Color.RED)
    }

    @Test
    fun `a free-form logo keeps a white plate of the shape`() {
        val circle = shapedIcon(cross(Color.GREEN), IconShape.CIRCLE, size)

        // The logo in the middle (0.64 of 144 is 92: from 26 to 118), the plate around it, nothing outside.
        assertThat(circle.getPixel(72, 72)).isEqualTo(Color.GREEN)
        assertThat(circle.getPixel(30, 72)).isEqualTo(Color.GREEN)
        assertThat(circle.getPixel(40, 40)).isEqualTo(Color.WHITE)
        assertThat(circle.getPixel(72, 12)).isEqualTo(Color.WHITE)
        assertThat(circle.clear(4, 4)).isTrue()
        // The plate has the picked shape.
        assertThat(shapedIcon(cross(Color.GREEN), IconShape.SQUARE, size).getPixel(8, 8)).isEqualTo(Color.WHITE)
        assertThat(shapedIcon(cross(Color.GREEN), IconShape.HEXAGON, size).clear(8, 8)).isTrue()
    }

    @Test
    fun `a free-form logo with empty margins is sized by what shows of it`() {
        // The cross covers only the middle half of its picture; on the plate it is as large as a whole one.
        val shaped = shapedIcon(cross(Color.GREEN, from = 25f, to = 75f), IconShape.SQUARE, size)

        assertThat(shaped.getPixel(30, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(114, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(40, 40)).isEqualTo(Color.WHITE)
    }

    // --- Whatever the icon is made of, it is enlarged as a whole ---------------------------------------

    @Test
    fun `an icon with fixed insets is enlarged with its insets, not cut by them`() {
        // A tile with a red rim, inset by a fixed 36 px on every side (an InsetDrawable does not scale its
        // insets with its bounds): drawn over 144 px its tile is the middle 72 px.
        val rimmed = Painted { p ->
            p.color = Color.RED
            drawRect(0f, 0f, 100f, 100f, p)
            p.color = Color.GREEN
            drawRect(10f, 10f, 90f, 90f, p)
        }
        val inset = android.graphics.drawable.InsetDrawable(rimmed, 36)

        val shaped = shapedIcon(inset, IconShape.SQUARE, size)

        // The whole tile fills the shape: the rim is still its outermost part, the green inside it.
        assertThat(shaped.getPixel(72, 4)).isEqualTo(Color.RED)
        assertThat(shaped.getPixel(4, 72)).isEqualTo(Color.RED)
        assertThat(shaped.getPixel(139, 72)).isEqualTo(Color.RED)
        assertThat(shaped.getPixel(72, 139)).isEqualTo(Color.RED)
        assertThat(shaped.getPixel(72, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(72, 24)).isEqualTo(Color.GREEN)
    }

    @Test
    fun `a free-form logo with fixed insets is sized with its insets too`() {
        val inset = android.graphics.drawable.InsetDrawable(cross(Color.GREEN), 36)

        val shaped = shapedIcon(inset, IconShape.SQUARE, size)

        // The cross spans the plate's inner box (26 to 118) whole: both ends of its arm are there.
        assertThat(shaped.getPixel(30, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(114, 72)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(72, 30)).isEqualTo(Color.GREEN)
        assertThat(shaped.getPixel(40, 40)).isEqualTo(Color.WHITE)
    }

    // --- An icon that is see-through all over is still an icon -----------------------------------------

    @Test
    fun `an icon that is faint all over is still drawn, on its plate`() {
        // Every pixel under the limit that counts as solid: it has no body to measure, but it shows.
        val faint = solid(Color.argb(96, 0, 255, 0))

        val shaped = shapedIcon(faint, IconShape.SQUARE, size)

        val middle = shaped.getPixel(72, 72)
        assertThat(middle).isNotEqualTo(Color.WHITE)
        assertThat(Color.green(middle)).isEqualTo(255)
        assertThat(Color.red(middle)).isLessThan(200)
        // On the plate, at the plate's size for an icon: white around it.
        assertThat(shaped.getPixel(10, 10)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `an icon with nothing to show leaves an empty plate`() {
        val shaped = shapedIcon(Painted { }, IconShape.SQUARE, size)

        assertThat(shaped.getPixel(72, 72)).isEqualTo(Color.WHITE)
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
