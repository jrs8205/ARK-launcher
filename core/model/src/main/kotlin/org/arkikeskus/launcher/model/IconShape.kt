package org.arkikeskus.launcher.model

import android.graphics.Path

/**
 * The shape every app icon is cut to (Settings, Icons; issue #26). [SYSTEM] is the phone's own shape, and
 * with it the launcher redraws nothing. The [id] is what the settings and a backup store: it never changes.
 */
enum class IconShape(val id: String) {
    SYSTEM("system"),
    CIRCLE("circle"),
    SQUIRCLE("squircle"),
    ROUNDED_SQUARE("rounded_square"),
    SQUARE("square"),
    TEARDROP("teardrop"),
    HEXAGON("hexagon"),
    OCTAGON("octagon"),
    FLOWER("flower"),
    ;

    companion object {
        /** The shape stored as [id]; a missing id or one this version does not know is the system's. */
        fun fromId(id: String?): IconShape = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * How large a free-form icon that is not adaptive is drawn on its plate, as a part of the icon's side (an
 * old icon that is a tile of its own fills the shape instead, see `shapedIcon`). It is the largest square,
 * to two decimals, that stays inside every shape; the hexagon is the tight one.
 */
const val LEGACY_ICON_SCALE = 0.64f

/** The outline of [shape] in a box of [size] by [size] pixels; null for the phone's own shape. */
fun iconShapePath(shape: IconShape, size: Float): Path? {
    val half = size / 2f
    return when (shape) {
        IconShape.SYSTEM -> null
        IconShape.CIRCLE -> Path().apply { addCircle(half, half, half, Path.Direction.CW) }
        // A superellipse of the fourth power: flatter sides than a circle, no straight ones.
        IconShape.SQUIRCLE -> polar(half) { cos, sin -> half / Math.pow(Math.pow(cos, 4.0) + Math.pow(sin, 4.0), 0.25).toFloat() }
        IconShape.ROUNDED_SQUARE -> roundedBox(size, size * 0.257f, size * 0.257f)
        IconShape.SQUARE -> roundedBox(size, size * 0.067f, size * 0.067f)
        // Round on three corners, nearly pointed at the bottom right.
        IconShape.TEARDROP -> roundedBox(size, half, size * 0.105f)
        IconShape.HEXAGON -> roundedPolygon(half, sides = 6, firstCornerDegrees = -90f, cornerRadius = half * 0.133f)
        IconShape.OCTAGON -> roundedPolygon(half, sides = 8, firstCornerDegrees = 22.5f, cornerRadius = half * 0.086f)
        // Eight shallow petals around a circle.
        IconShape.FLOWER -> polar(half) { cos, sin ->
            val angle = Math.atan2(sin, cos)
            half * (0.93f + 0.07f * Math.cos(8 * angle).toFloat())
        }
    }
}

/** A box of [size] with the corner radius [radius], except [bottomRight] at its bottom right corner. */
private fun roundedBox(size: Float, radius: Float, bottomRight: Float): Path = Path().apply {
    addRoundRect(
        0f, 0f, size, size,
        floatArrayOf(radius, radius, radius, radius, bottomRight, bottomRight, radius, radius),
        Path.Direction.CW,
    )
}

/** A closed outline around the middle of a box of twice [half]: [radius] gives the distance at each direction. */
private fun polar(half: Float, radius: (cos: Double, sin: Double) -> Float): Path = Path().apply {
    for (step in 0 until POLAR_STEPS) {
        val angle = 2 * Math.PI * step / POLAR_STEPS
        val cos = Math.cos(angle)
        val sin = Math.sin(angle)
        val r = radius(cos, sin)
        val x = half + r * cos.toFloat()
        val y = half + r * sin.toFloat()
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * A regular polygon with rounded corners whose corners touch the box of twice [half]: each corner is an
 * arc of [cornerRadius] around a point that much nearer the middle, and the sides join the arcs.
 */
private fun roundedPolygon(half: Float, sides: Int, firstCornerDegrees: Float, cornerRadius: Float): Path = Path().apply {
    val centres = half - cornerRadius
    val sweep = 360f / sides
    for (corner in 0 until sides) {
        val degrees = firstCornerDegrees + corner * sweep
        val radians = Math.toRadians(degrees.toDouble())
        val x = half + centres * Math.cos(radians).toFloat()
        val y = half + centres * Math.sin(radians).toFloat()
        arcTo(x - cornerRadius, y - cornerRadius, x + cornerRadius, y + cornerRadius, degrees - sweep / 2, sweep, corner == 0)
    }
    close()
}

/** Points on a [polar] outline: one per degree is smooth at any icon size the launcher draws. */
private const val POLAR_STEPS = 360
