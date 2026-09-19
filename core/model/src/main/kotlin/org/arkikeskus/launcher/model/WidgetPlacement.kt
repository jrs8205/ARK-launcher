package org.arkikeskus.launcher.model

/** A proposed widget footprint. Persistence revalidates it against the latest layout. */
data class WidgetPlacement(
    val page: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
)
