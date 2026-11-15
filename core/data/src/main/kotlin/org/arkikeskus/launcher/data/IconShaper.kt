package org.arkikeskus.launcher.data

import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import org.arkikeskus.launcher.model.IconShape
import org.arkikeskus.launcher.model.shapedIcon

/** Rasterization size (px) of an icon; crisp for icons up to ~56dp at xxxhdpi. */
internal const val ICON_PX = 168

/**
 * An app's own [icon] in the [shape] the user picked (issue #26). The phone's own shape means the icon is
 * handed on untouched, so nothing looks different until a shape is picked; any other shape gives a
 * picture already cut to it (the drawing itself is the model's [shapedIcon]).
 */
internal fun ownIconInShape(icon: Drawable, shape: IconShape, resources: Resources): Drawable =
    if (shape == IconShape.SYSTEM) icon else shapedIcon(icon, shape, ICON_PX).toDrawable(resources)
