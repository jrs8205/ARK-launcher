package org.arkikeskus.launcher.feature.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
import android.content.Context
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.arkikeskus.launcher.data.HomeLayoutRepository

/** App-unique id for our single AppWidgetHost ("ARK1"). */
const val APPWIDGET_HOST_ID = 0x41524B31

/** The Activity-owned AppWidgetHost (start/stop tied to the Activity lifecycle); null outside the launcher. */
val LocalAppWidgetHost = staticCompositionLocalOf<AppWidgetHost?> { null }

/**
 * Launches a placed widget's configuration activity via the SYSTEM (Activity-routed
 * `AppWidgetHost.startAppWidgetConfigureActivityForResult`), invoking [onResult] with whether it
 * completed (RESULT_OK). Must go through the system so the framework records the widget as configured —
 * a raw `ACTION_APPWIDGET_CONFIGURE` Intent leaves it "configuration pending", which makes some
 * providers (e.g. WhatsApp, whose config is an auth screen) refuse to populate. Provided by
 * LauncherActivity; null outside it.
 */
val LocalWidgetConfigLauncher =
    staticCompositionLocalOf<((appWidgetId: Int, onResult: (Boolean) -> Unit) -> Unit)?> { null }

/**
 * A widget-config activity result that returned AFTER the launcher process was recreated (the
 * in-memory [LocalWidgetConfigLauncher] callback died with the old process). The home screen
 * consumes it against its saveable pending-bind state and clears the value. Null outside the launcher.
 */
val LocalOrphanWidgetConfigResult = staticCompositionLocalOf<MutableStateFlow<Boolean?>?> { null }

/**
 * Default span from API 31+ cell hints and the minimum physical size on this home grid.
 */
fun defaultWidgetSpans(
    provider: AppWidgetProviderInfo, context: Context, cellWidthDp: Float, cellHeightDp: Float,
): Pair<Int, Int> {
    val density = context.resources.displayMetrics.density
    val hintX = if (Build.VERSION.SDK_INT >= 31) provider.targetCellWidth else 0
    val hintY = if (Build.VERSION.SDK_INT >= 31) provider.targetCellHeight else 0
    val sx = maxOf(hintX, minimumWidgetCells(provider.minWidth / density, cellWidthDp))
    val sy = maxOf(hintY, minimumWidgetCells(provider.minHeight / density, cellHeightDp))
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
fun widgetResizeRange(
    info: AppWidgetProviderInfo,
    context: Context,
    gridColumns: Int,
    rows: Int = HomeLayoutRepository.ROWS,
    cellWidthDp: Float,
    cellHeightDp: Float,
): WidgetResizeRange {
    val density = context.resources.displayMetrics.density
    val minX = minimumWidgetCells(
        (if (info.minResizeWidth > 0) minOf(info.minResizeWidth, info.minWidth) else info.minWidth) / density, cellWidthDp,
    )
    val minY = minimumWidgetCells(
        (if (info.minResizeHeight > 0) minOf(info.minResizeHeight, info.minHeight) else info.minHeight) / density, cellHeightDp,
    )
    val maxX = maximumWidgetCells(if (Build.VERSION.SDK_INT >= 31) info.maxResizeWidth / density else 0f, cellWidthDp, gridColumns)
    val maxY = maximumWidgetCells(if (Build.VERSION.SDK_INT >= 31) info.maxResizeHeight / density else 0f, cellHeightDp, rows)
    val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    return WidgetResizeRange(
        minX = minX,
        minY = minY,
        maxX = maxOf(minX, maxX),
        maxY = maxOf(minY, maxY),
        horizontal = horizontal && minX <= maxX,
        vertical = vertical && minY <= maxY,
    )
}

/** True if [info] has a configuration activity AND declares the reconfigurable feature (Launcher3's
 *  `isReconfigurable()` gate). Such widgets can be re-configured after placement. */
fun isReconfigurableWidget(info: AppWidgetProviderInfo): Boolean =
    info.configure != null && (info.widgetFeatures and WIDGET_FEATURE_RECONFIGURABLE) != 0
