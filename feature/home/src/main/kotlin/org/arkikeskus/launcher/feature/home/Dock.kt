package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon
import kotlin.math.roundToInt

/**
 * Bottom dock: a translucent rounded bar of favorite app icons. Tap to launch; long-press and
 * drag an icon sideways to reorder (persisted via [onReorder]).
 */
@Composable
fun Dock(
    apps: List<AppItem>,
    showLabels: Boolean,
    backgroundAlpha: Float,
    onAppClick: (AppItem) -> Unit,
    onReorder: (List<AppItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = backgroundAlpha),
        shape = RoundedCornerShape(30.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .onSizeChanged { rowWidthPx = it.width },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            apps.forEachIndexed { index, app ->
                val isDragging = index == draggingIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationX = if (isDragging) dragOffsetX else 0f }
                        .pointerInput(app.key, apps.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = index
                                    dragOffsetX = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffsetX += amount.x
                                },
                                onDragEnd = {
                                    val slot = if (apps.isNotEmpty()) rowWidthPx.toFloat() / apps.size else 1f
                                    val shift = if (slot > 0f) (dragOffsetX / slot).roundToInt() else 0
                                    val target = (index + shift).coerceIn(0, apps.size - 1)
                                    if (target != index) {
                                        val reordered = apps.toMutableList().also {
                                            it.add(target, it.removeAt(index))
                                        }
                                        onReorder(reordered)
                                    }
                                    draggingIndex = -1
                                    dragOffsetX = 0f
                                },
                                onDragCancel = {
                                    draggingIndex = -1
                                    dragOffsetX = 0f
                                },
                            )
                        }
                        .clickable { onAppClick(app) },
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(
                        appItem = app,
                        labelColor = Color.White,
                        showLabel = showLabels,
                        iconSize = 52.dp,
                    )
                }
            }
        }
    }
}
