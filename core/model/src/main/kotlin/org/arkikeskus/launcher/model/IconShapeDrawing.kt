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

/** What an icon that is not adaptive stands on when a shape is picked, as on Android's own launcher. */
private const val LEGACY_PLATE = Color.WHITE

/**
 * [icon] drawn into a bitmap of [sizePx] and cut to [shape], which is never the system's here.
 *
 * An adaptive icon is drawn from its two layers, on black as Android draws it, so the launcher's outline
 * takes the place of the phone's mask and nothing else about the icon changes: a layer is 108 dp for the
 * 72 dp that shows, half as large again as the icon. Any other
 * icon is a finished picture with a form of its own; it is drawn smaller on a white plate of the shape,
 * so a row of icons has one outline.
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
        canvas.drawColor(LEGACY_PLATE)
        val inset = (sizePx * (1f - LEGACY_ICON_SCALE) / 2f).roundToInt()
        val own = icon.copyBounds()
        icon.setBounds(inset, inset, sizePx - inset, sizePx - inset)
        icon.draw(canvas)
        icon.bounds = own
    }
    return cut(whole, outline, sizePx)
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
