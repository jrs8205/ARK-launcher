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

/** An outlined battery glyph whose outline and fill use [color] (the dynamic battery color). */
@Composable
fun BatteryBar(
    percent: Int,
    color: Color,
    modifier: Modifier = Modifier,
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
        val fillWidth = (innerMax * (percent.coerceIn(0, 100) / 100f)).coerceAtLeast(0f)
        drawRoundRect(
            color = color,
            topLeft = Offset(innerLeft, stroke * 2.5f),
            size = Size(fillWidth, size.height - stroke * 5),
            cornerRadius = CornerRadius(size.height * 0.12f),
        )
    }
}
