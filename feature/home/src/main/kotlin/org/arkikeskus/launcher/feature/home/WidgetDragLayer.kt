package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.data.ReorderPlanner
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.ui.HomeDragController
import kotlin.math.roundToInt

internal fun List<HomeEntry>.widgetRects() = mapIndexed { index, e ->
    val span = when (e) {
        is PlacedWidget -> e.spanX to e.spanY
        is PendingWidget -> e.spanX to e.spanY
        is PlacedBuiltin -> e.spanX to e.spanY
        else -> 1 to 1
    }
    val id = when (e) {
        is PlacedWidget -> e.rowId
        is PendingWidget -> e.rowId
        is PlacedBuiltin -> e.rowId
        else -> -(index.toLong() + 1)
    }
    ReorderPlanner.Rect(id, e.page, e.cellX, e.cellY, span.first, span.second)
}

@Composable
internal fun BuiltinWidget(type: String, modifier: Modifier = Modifier) {
    when (type) {
        HomeItemEntity.BUILTIN_NOTIFICATIONS -> NotificationsWidget(modifier)
        HomeItemEntity.BUILTIN_BATTERY -> BatteryWidget(modifier)
        else -> SmartspaceWidget(modifier)
    }
}

/** Only this small overlay reads the per-frame position in composition. */
@Composable
internal fun WidgetDragLayer(state: WidgetDragController, home: HomeDragController, cellW: Float, cellH: Float) {
    val drag = state.active ?: return
    if (!state.moving) return
    val density = LocalDensity.current
    val target = state.target?.invoke()
    val removing = drag.item != null && home.isOverRemove(state.position)
    val color = if (target == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    if (target != null) {
        Box(Modifier.offset { IntOffset((target.cellX * cellW).roundToInt(), (target.cellY * cellH).roundToInt()) }
            .size(with(density) { (drag.spanX * cellW).toDp() }, with(density) { (drag.spanY * cellH).toDp() })
            .padding(4.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .border(2.dp, color, RoundedCornerShape(24.dp)))
    }
    Column(Modifier.offset {
        val p = state.position - home.gridBounds.topLeft
        IntOffset((p.x - drag.spanX * cellW * drag.grabFraction.x).roundToInt(),
            (p.y - drag.spanY * cellH * drag.grabFraction.y).roundToInt())
    }) {
        Box(Modifier.size(with(density) { (drag.spanX * cellW).toDp() }, with(density) { (drag.spanY * cellH).toDp() })
            .graphicsLayer { alpha = 0.90f; scaleX = 1.02f; scaleY = 1.02f }) {
            val builtin = drag.choice as? WidgetChoice.Builtin
            when {
                drag.preview != null -> Image(drag.preview.asImageBitmap(), null, Modifier.fillMaxSize())
                builtin != null -> BuiltinWidget(builtin.type, Modifier.fillMaxSize())
                drag.choice != null -> WidgetPreview(drag.choice, Modifier.fillMaxSize(),
                    drag.spanX * state.cellWidthDp, drag.spanY * state.cellHeightDp, onSnapshot = {})
                else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)))
            }
        }
        // No size readout while dragging (no other launcher shows one); the label appears only
        // when the drop would do something other than place the widget here.
        if (removing || target == null) Text(
            if (removing) stringResource(R.string.drag_remove) else stringResource(R.string.widget_no_space),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
