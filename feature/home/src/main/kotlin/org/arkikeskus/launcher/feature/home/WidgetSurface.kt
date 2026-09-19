package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LocalTonalWidgets = staticCompositionLocalOf { false }

@Composable
internal fun widgetContentColor(): Color =
    if (LocalTonalWidgets.current) MaterialTheme.colorScheme.onSurface else Color.White

@Composable
internal fun widgetSurfaceColor(): Color =
    if (LocalTonalWidgets.current) MaterialTheme.colorScheme.surfaceContainer else Color.Black.copy(alpha = 0.30f)

@Composable
internal fun WidgetSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(24.dp),
        color = widgetSurfaceColor(), contentColor = widgetContentColor()) {
        Box(content = content)
    }
}
