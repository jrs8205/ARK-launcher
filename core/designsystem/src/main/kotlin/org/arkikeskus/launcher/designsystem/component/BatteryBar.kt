package org.arkikeskus.launcher.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * How much of the battery glyph is filled, from 0 to 1: the level ([percent]), and while charging a [sweep]
 * (0 to 1) that runs from the level up to full. The sweep never shows less than the level.
 */
fun batteryFillFraction(percent: Int, sweep: Float): Float {
    val level = percent.coerceIn(0, 100) / 100f
    return level + (1f - level) * sweep.coerceIn(0f, 1f)
}

/** How strong the sweep's part of the fill is beside the level's: the level stays the part that reads as the charge. */
private const val SWEEP_ALPHA = 0.45f

/**
 * An outlined battery glyph whose outline and fill use [color] (the dynamic battery color). [sweep], when
 * given, is read while drawing: the charging animation's sweep from the level up to full, drawn fainter
 * than the level itself. Reading it there costs a redraw of this glyph a frame, and no recomposition.
 */
@Composable
fun BatteryBar(
    percent: Int,
    color: Color,
    modifier: Modifier = Modifier,
    sweep: (() -> Float)? = null,
) {
    Canvas(modifier = modifier.size(width = 23.dp, height = 12.dp)) {
        val nubWidth = size.width * 0.06f
        val bodyWidth = size.width - nubWidth - 1f
        val stroke = (size.height * 0.11f).coerceAtLeast(1.5f)

        // Body outline
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke, stroke),
            size = Size(bodyWidth - stroke * 2, size.height - stroke * 2),
            cornerRadius = CornerRadius(size.height * 0.25f),
            style = Stroke(width = stroke),
        )
        // Positive terminal nub
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyWidth, size.height * 0.32f),
            size = Size(nubWidth, size.height * 0.36f),
            cornerRadius = CornerRadius(nubWidth * 0.5f),
        )
        // Fill proportional to charge
        val innerLeft = stroke * 2.5f
        val innerMax = bodyWidth - stroke * 5
        val fillWidth = (innerMax * batteryFillFraction(percent, 0f)).coerceAtLeast(0f)
        val swept = sweep?.invoke() ?: 0f
        if (swept > 0f) {
            drawRoundRect(
                color = color.copy(alpha = color.alpha * SWEEP_ALPHA),
                topLeft = Offset(innerLeft, stroke * 2.5f),
                size = Size((innerMax * batteryFillFraction(percent, swept)).coerceAtLeast(0f), size.height - stroke * 5),
                cornerRadius = CornerRadius(size.height * 0.12f),
            )
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(innerLeft, stroke * 2.5f),
            size = Size(fillWidth, size.height - stroke * 5),
            cornerRadius = CornerRadius(size.height * 0.12f),
        )
    }
}
