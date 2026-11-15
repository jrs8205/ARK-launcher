package org.arkikeskus.launcher.model

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/*
 * Drawing an icon in the shape the user picked (issue #26). It lives beside the outlines because two places
 * draw with it and see only this module: the image loader's fetchers (app icons, picked pictures) and the
 * home screen's pinned shortcuts.
 */

/** What a free-form old icon stands on when a shape is picked, as on Android's own launcher. */
private const val LEGACY_PLATE = Color.WHITE

/**
 * From this share of its own outline's box filled, an old icon counts as a tile: a picture with a body of
 * its own (a rounded square, a disc: a disc fills 0.785 of its box) that can be enlarged to fill a shape.
 * Below it the icon is a free-form logo, which a shape would cut through.
 */
private const val LEGACY_TILE_COVERAGE = 0.74f

/** A pixel at least this opaque is a part of the icon. The soft shadow many old icons carry is fainter. */
private const val LEGACY_SOLID_ALPHA = 128

/** A pixel at least this opaque has the icon's own colour (an anti-aliased rim pixel has a mix). */
private const val LEGACY_FULL_ALPHA = 250

/**
 * [icon] drawn into a bitmap of [sizePx] and cut to [shape], which is never the system's here.
 *
 * An adaptive icon is drawn from its two layers, on black as Android draws it, so the launcher's outline
 * takes the place of the phone's mask and nothing else about the icon changes: a layer is 108 dp for the
 * 72 dp that shows, half as large again as the icon. Any other icon is a finished picture with a form of
 * its own, and is drawn as [drawOldIcon] says: filling the shape where it can, on a plate where it cannot.
 */
fun shapedIcon(icon: Drawable, shape: IconShape, sizePx: Int): Bitmap {
    val outline = requireNotNull(iconShapePath(shape, sizePx.toFloat())) { "the system's shape is not drawn by the launcher" }
    val whole = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(whole)
    if (icon is AdaptiveIconDrawable) {
        // Android's own drawing lays the layers on black (AdaptiveIconDrawable.draw fills its canvas first).
        // Without it a background that is see-through, or missing, would let the wallpaper show through
        // the icon, and a half see-through one would change colour, only because a shape was picked.
        canvas.drawColor(Color.BLACK)
        val extra = (sizePx * AdaptiveIconDrawable.getExtraInsetFraction()).roundToInt()
        for (layer in listOfNotNull(icon.background, icon.foreground)) {
            val own = layer.copyBounds()
            layer.setBounds(-extra, -extra, sizePx + extra, sizePx + extra)
            layer.draw(canvas)
            layer.bounds = own
        }
    } else {
        drawOldIcon(icon, canvas, sizePx)
    }
    return cut(whole, outline, sizePx)
}

/**
 * An icon without layers, drawn to fill a box of [sizePx] before the shape is cut out of it (issue #26; the
 * requester's own example of a launcher doing it badly was an old icon sitting small on a white plate).
 *
 * - An icon that is a **tile** of its own (see [LEGACY_TILE_COVERAGE]) is enlarged until its body fills the
 *   box, whatever empty margin its picture had, and whatever of the box it still leaves (its own rounder
 *   corners, the sides of a disc) takes the colour of its own edge. It then looks like any other icon.
 * - A **free-form logo** cannot fill a shape without being cut, so it keeps a white plate; it is sized by
 *   what shows of it, to [LEGACY_ICON_SCALE] of the box, which stays inside every shape.
 */
private fun drawOldIcon(icon: Drawable, canvas: Canvas, sizePx: Int) {
    val own = icon.copyBounds()
    val body = bodyOf(icon, sizePx)
    when {
        body == null -> canvas.drawColor(LEGACY_PLATE)
        body.isTile -> {
            canvas.drawColor(body.edgeColour)
            icon.drawWithBodyIn(canvas, body, sizePx, boxStart = 0f, boxSide = sizePx.toFloat())
        }
        else -> {
            canvas.drawColor(LEGACY_PLATE)
            val inset = sizePx * (1f - LEGACY_ICON_SCALE) / 2f
            icon.drawWithBodyIn(canvas, body, sizePx, boxStart = inset, boxSide = sizePx - 2 * inset)
        }
    }
    icon.bounds = own
}

/** What shows of an old icon when it is drawn over a box of [sizePx]: where its solid pixels are, and what they are like. */
private class IconBody(val left: Int, val top: Int, val right: Int, val bottom: Int, val isTile: Boolean, val edgeColour: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Draws [icon] once over a box of [sizePx] and looks at it; null when nothing of it is solid. */
private fun bodyOf(icon: Drawable, sizePx: Int): IconBody? {
    val seen = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    icon.setBounds(0, 0, sizePx, sizePx)
    icon.draw(Canvas(seen))
    val pixels = IntArray(sizePx * sizePx)
    seen.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    seen.recycle()

    var left = sizePx
    var top = sizePx
    var right = -1
    var bottom = -1
    var solid = 0
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            if (Color.alpha(pixels[y * sizePx + x]) >= LEGACY_SOLID_ALPHA) {
                solid++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    if (solid == 0) return null
    right++
    bottom++
    val width = right - left
    val height = bottom - top
    val isTile = solid >= LEGACY_TILE_COVERAGE * width * height

    // The colour of the body's edge: its fully opaque pixels in a band along the four sides of its box.
    val band = maxOf(2, (maxOf(width, height) * 0.06f).roundToInt())
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0
    for (y in top until bottom) {
        for (x in left until right) {
            val inBand = x < left + band || x >= right - band || y < top + band || y >= bottom - band
            val pixel = pixels[y * sizePx + x]
            if (inBand && Color.alpha(pixel) >= LEGACY_FULL_ALPHA) {
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
    }
    val edgeColour = if (count == 0) LEGACY_PLATE else Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    return IconBody(left, top, right, bottom, isTile, edgeColour)
}

/**
 * Draws the icon so that its [body], found in a drawing over [sizePx], is centred in the square box that
 * begins at [boxStart] (both ways) and has the side [boxSide], as large as fits: the longer side of the
 * body takes the whole side of the box.
 */
private fun Drawable.drawWithBodyIn(canvas: Canvas, body: IconBody, sizePx: Int, boxStart: Float, boxSide: Float) {
    val scale = boxSide / maxOf(body.width, body.height)
    val boxMiddle = boxStart + boxSide / 2f
    val left = boxMiddle - (body.left + body.width / 2f) * scale
    val top = boxMiddle - (body.top + body.height / 2f) * scale
    setBounds(left.roundToInt(), top.roundToInt(), (left + sizePx * scale).roundToInt(), (top + sizePx * scale).roundToInt())
    draw(canvas)
}

/** [picture] scaled to fill a box of [sizePx] and cut to [outline]: a gallery picture picked as an icon. */
fun shapedPicture(picture: Bitmap, outline: Path, sizePx: Int): Bitmap = cut(picture, outline, sizePx)

/**
 * The outline a gallery picture is cut to: the picked [shape]'s, or for the system's shape the phone's own
 * icon mask, so the picture sits among the other icons like one of them.
 */
fun pictureOutline(shape: IconShape, sizePx: Int): Path =
    iconShapePath(shape, sizePx.toFloat())
        ?: AdaptiveIconDrawable(ColorDrawable(Color.BLACK), null).apply { setBounds(0, 0, sizePx, sizePx) }.iconMask

/**
 * [source] stretched over a box of [sizePx] and painted through [outline]. Painting the outline with the
 * picture as its shader, rather than clipping, gives a smooth edge on every Android version.
 */
private fun cut(source: Bitmap, outline: Path, sizePx: Int): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(sizePx / source.width.toFloat(), sizePx / source.height.toFloat()) })
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { Canvas(it).drawPath(outline, paint) }
}
