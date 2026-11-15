package org.arkikeskus.launcher.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import org.arkikeskus.launcher.model.IconShape
import org.arkikeskus.launcher.model.LEGACY_ICON_SCALE
import org.arkikeskus.launcher.model.iconShapePath
import kotlin.math.roundToInt

/** Rasterization size (px) of an icon; crisp for icons up to ~56dp at xxxhdpi. */
internal const val ICON_PX = 168

/** What an icon that is not adaptive stands on when a shape is picked, as on Android's own launcher. */
private const val LEGACY_PLATE = Color.WHITE

/**
 * An app's own [icon] in the [shape] the user picked (issue #26). The phone's own shape means the icon is
 * handed on untouched, so nothing looks different until a shape is picked; any other shape gives a
 * picture already cut to it.
 */
internal fun ownIconInShape(icon: Drawable, shape: IconShape, resources: Resources): Drawable =
    if (shape == IconShape.SYSTEM) icon else shapedIcon(icon, shape).toDrawable(resources)

/**
 * [icon] drawn into a bitmap of [sizePx] and cut to [shape], which is never the system's here.
 *
 * An adaptive icon is drawn from its two layers, so the launcher's outline takes the place of the
 * phone's mask: a layer is 108 dp for the 72 dp that shows, half as large again as the icon. Any other
 * icon is a finished picture with a form of its own; it is drawn smaller on a white plate of the shape,
 * so a row of icons has one outline.
 */
internal fun shapedIcon(icon: Drawable, shape: IconShape, sizePx: Int = ICON_PX): Bitmap {
    val outline = requireNotNull(iconShapePath(shape, sizePx.toFloat())) { "the system's shape is not drawn by the launcher" }
    val whole = createBitmap(sizePx, sizePx)
    val canvas = Canvas(whole)
    if (icon is AdaptiveIconDrawable) {
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
internal fun shapedPicture(picture: Bitmap, outline: Path, sizePx: Int = ICON_PX): Bitmap = cut(picture, outline, sizePx)

/**
 * The outline a gallery picture is cut to: the picked [shape]'s, or for the system's shape the phone's own
 * icon mask, so the picture sits among the other icons like one of them.
 */
internal fun pictureOutline(shape: IconShape, sizePx: Int = ICON_PX): Path =
    iconShapePath(shape, sizePx.toFloat())
        ?: AdaptiveIconDrawable(Color.BLACK.toDrawable(), null).apply { setBounds(0, 0, sizePx, sizePx) }.iconMask

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
    return createBitmap(sizePx, sizePx).also { Canvas(it).drawPath(outline, paint) }
}
