package org.arkikeskus.launcher.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The icon shapes' outlines, looked at as they are drawn: filled into a 100 px box and read pixel by pixel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconShapeTest {
    private val size = 100
    private val drawn = IconShape.entries.filter { it != IconShape.SYSTEM }

    private fun filled(shape: IconShape): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPath(iconShapePath(shape, size.toFloat())!!, Paint().apply { color = Color.BLACK })
        return bitmap
    }

    private fun Bitmap.inside(x: Int, y: Int): Boolean = Color.alpha(getPixel(x, y)) == 255
    private fun Bitmap.outside(x: Int, y: Int): Boolean = Color.alpha(getPixel(x, y)) == 0

    @Test
    fun `the ids are the ones a backup carries`() {
        assertThat(IconShape.entries.map { it.id }).containsExactly(
            "system", "circle", "squircle", "rounded_square", "square", "teardrop", "hexagon", "octagon", "flower",
        ).inOrder()
    }

    @Test
    fun `an id reads back as its shape, and anything else as the system's`() {
        for (shape in IconShape.entries) assertThat(IconShape.fromId(shape.id)).isEqualTo(shape)
        assertThat(IconShape.fromId("star")).isEqualTo(IconShape.SYSTEM)
        assertThat(IconShape.fromId(null)).isEqualTo(IconShape.SYSTEM)
    }

    @Test
    fun `the system's shape has no outline of the launcher's own`() {
        assertThat(iconShapePath(IconShape.SYSTEM, 100f)).isNull()
    }

    @Test
    fun `every shape stays inside its box and covers its middle`() {
        // Drawn with ten pixels of room around the box: nothing may land in that room.
        val margin = 10
        for (shape in drawn) {
            val bitmap = Bitmap.createBitmap(size + 2 * margin, size + 2 * margin, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { translate(margin.toFloat(), margin.toFloat()) }
                .drawPath(iconShapePath(shape, size.toFloat())!!, Paint().apply { color = Color.BLACK })
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val inBox = x in margin until margin + size && y in margin until margin + size
                    // An edge that lies exactly on the box's side leaves a trace of anti-aliasing (alpha 2 of 255) beside it.
                    if (!inBox) assertWithMessage("$shape draws outside its box at $x,$y").that(Color.alpha(bitmap.getPixel(x, y))).isLessThan(8)
                }
            }
            assertWithMessage("$shape middle").that(filled(shape).inside(50, 50)).isTrue()
        }
    }

    @Test
    fun `every shape reaches the box's edges, so an icon is as large as it was`() {
        // Top and bottom for all; the hexagon stands on a point and is narrower than it is tall.
        for (shape in drawn) {
            val bitmap = filled(shape)
            assertWithMessage("$shape top").that((0..5).any { bitmap.inside(50, it) }).isTrue()
            assertWithMessage("$shape bottom").that((94..99).any { bitmap.inside(50, it) }).isTrue()
        }
    }

    @Test
    fun `a circle leaves the corners out`() {
        val circle = filled(IconShape.CIRCLE)
        assertThat(circle.outside(4, 4)).isTrue()
        assertThat(circle.outside(95, 95)).isTrue()
        assertThat(circle.inside(50, 2)).isTrue()
        assertThat(circle.inside(2, 50)).isTrue()
    }

    @Test
    fun `a squircle is fuller than a circle and still rounder than a square`() {
        val squircle = filled(IconShape.SQUIRCLE)
        assertThat(squircle.inside(10, 10)).isTrue()
        assertThat(filled(IconShape.CIRCLE).outside(10, 10)).isTrue()
        assertThat(squircle.outside(3, 3)).isTrue()
    }

    @Test
    fun `the two squares differ by their corners`() {
        assertThat(filled(IconShape.SQUARE).inside(4, 4)).isTrue()
        assertThat(filled(IconShape.SQUARE).outside(0, 0)).isTrue()
        assertThat(filled(IconShape.ROUNDED_SQUARE).outside(4, 4)).isTrue()
        assertThat(filled(IconShape.ROUNDED_SQUARE).inside(12, 12)).isTrue()
    }

    @Test
    fun `a teardrop is a circle with one pointed corner, at the bottom right`() {
        val teardrop = filled(IconShape.TEARDROP)
        assertThat(teardrop.inside(95, 95)).isTrue()
        assertThat(teardrop.outside(4, 4)).isTrue()
        assertThat(teardrop.outside(95, 4)).isTrue()
        assertThat(teardrop.outside(4, 95)).isTrue()
    }

    @Test
    fun `a hexagon stands on a point`() {
        val hexagon = filled(IconShape.HEXAGON)
        assertThat(hexagon.inside(50, 2)).isTrue()
        assertThat(hexagon.inside(50, 97)).isTrue()
        // Its flat sides are left and right, a little inside the box.
        assertThat(hexagon.outside(3, 50)).isTrue()
        assertThat(hexagon.inside(8, 50)).isTrue()
        assertThat(hexagon.outside(8, 8)).isTrue()
    }

    @Test
    fun `an octagon lies on a flat side`() {
        val octagon = filled(IconShape.OCTAGON)
        assertThat(octagon.inside(50, 5)).isTrue()
        assertThat(octagon.inside(30, 5)).isTrue()
        assertThat(octagon.inside(5, 50)).isTrue()
        assertThat(octagon.outside(8, 8)).isTrue()
    }

    @Test
    fun `a flower has eight petals`() {
        val flower = filled(IconShape.FLOWER)
        // A petal points straight right, up, left and down, and one between each two of them.
        assertThat(flower.inside(98, 50)).isTrue()
        assertThat(flower.inside(50, 2)).isTrue()
        // Between two petals (22.5 degrees) the edge is drawn in: 47 px from the middle is outside there.
        val x = (50 + 47 * kotlin.math.cos(Math.toRadians(22.5))).toInt()
        val y = (50 + 47 * kotlin.math.sin(Math.toRadians(22.5))).toInt()
        assertThat(flower.outside(x, y)).isTrue()
        assertThat(filled(IconShape.CIRCLE).inside(x, y)).isTrue()
    }

    @Test
    fun `an old icon drawn at the legacy scale stays inside every shape`() {
        val half = size * LEGACY_ICON_SCALE / 2f
        val low = kotlin.math.ceil(50 - half).toInt()
        val high = kotlin.math.floor(50 + half).toInt() - 1
        for (shape in drawn) {
            val bitmap = filled(shape)
            for (i in low..high) {
                // The four sides of the square the icon is drawn into.
                assertWithMessage("$shape top at $i").that(bitmap.inside(i, low)).isTrue()
                assertWithMessage("$shape bottom at $i").that(bitmap.inside(i, high)).isTrue()
                assertWithMessage("$shape left at $i").that(bitmap.inside(low, i)).isTrue()
                assertWithMessage("$shape right at $i").that(bitmap.inside(high, i)).isTrue()
            }
        }
    }

    @Test
    fun `the legacy scale is no smaller than it has to be`() {
        // A tenth larger and the hexagon, the tight one, would cut the icon's corners.
        val half = size * (LEGACY_ICON_SCALE + 0.1f) / 2f
        val corner = kotlin.math.ceil(50 - half).toInt()
        assertThat(filled(IconShape.HEXAGON).inside(corner, corner)).isFalse()
    }
}
