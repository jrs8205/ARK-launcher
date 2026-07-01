package org.arkikeskus.launcher.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.designsystem.theme.LocalLauncherColors

/** Four ascending signal bars; the first [level] bars use [activeColor], the rest are faint. */
@Composable
fun SignalBars(
    level: Int,
    activeColor: Color,
    modifier: Modifier = Modifier,
) {
    val faint = LocalLauncherColors.current.signalFaint
    Canvas(modifier = modifier.size(width = 14.dp, height = 11.dp)) {
        val bars = 4
        val gap = size.width * 0.12f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val barHeight = size.height * (0.4f + 0.2f * i)
            val left = i * (barWidth + gap)
            val top = size.height - barHeight
            drawRoundRect(
                color = if (i < level) activeColor else faint,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * 0.3f),
            )
        }
    }
}
