package org.arkikeskus.launcher.feature.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import org.arkikeskus.launcher.data.HomeLayoutRepository

/** App-unique id for our single AppWidgetHost ("ARK1"). */
const val APPWIDGET_HOST_ID = 0x41524B31

/** The Activity-owned AppWidgetHost (start/stop tied to the Activity lifecycle); null outside the launcher. */
val LocalAppWidgetHost = staticCompositionLocalOf<AppWidgetHost?> { null }

/**
 * Default home-grid span for [provider]. Prefers the API 31+ cell hints; otherwise converts the
 * provider's min size (px) to dp and applies the classic `(dp + 30) / 70` heuristic. Min 1×1.
 */
fun defaultWidgetSpans(provider: AppWidgetProviderInfo, context: Context): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= 31 && provider.targetCellWidth > 0 && provider.targetCellHeight > 0) {
        return provider.targetCellWidth to provider.targetCellHeight
    }
    val density = context.resources.displayMetrics.density
    val wDp = provider.minWidth / density
    val hDp = provider.minHeight / density
    val sx = ((wDp + 30) / 70).toInt().coerceAtLeast(1)
    val sy = ((hDp + 30) / 70).toInt().coerceAtLeast(1)
    return sx to sy
}

/** The cell-span limits + allowed axes for resizing a widget, derived from its provider. */
data class WidgetResizeRange(
    val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
    val horizontal: Boolean, val vertical: Boolean,
) {
    val isResizable: Boolean get() = horizontal || vertical
}

/** Resize limits for [info]: min/max cells per axis (provider min/maxResize + grid), allowed axes. */
fun widgetResizeRange(info: AppWidgetProviderInfo, context: Context, gridColumns: Int): WidgetResizeRange {
    val density = context.resources.displayMetrics.density
    fun cells(px: Int) = ((px / density + 30) / 70).toInt().coerceAtLeast(1)
    val rows = HomeLayoutRepository.ROWS
    val minX = if (info.minResizeWidth > 0) cells(info.minResizeWidth) else 1
    val minY = if (info.minResizeHeight > 0) cells(info.minResizeHeight) else 1
    val maxX = if (Build.VERSION.SDK_INT >= 31 && info.maxResizeWidth > 0) cells(info.maxResizeWidth) else gridColumns
    val maxY = if (Build.VERSION.SDK_INT >= 31 && info.maxResizeHeight > 0) cells(info.maxResizeHeight) else rows
    val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    return WidgetResizeRange(
        minX = minX.coerceAtMost(maxX.coerceAtLeast(1)),
        minY = minY.coerceAtMost(maxY.coerceAtLeast(1)),
        maxX = maxX.coerceAtLeast(minX),
        maxY = maxY.coerceAtLeast(minY),
        horizontal = horizontal,
        vertical = vertical,
    )
}
