package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.WallpaperLook
import org.arkikeskus.launcher.model.contentIsDarkOn

internal val LocalAutomaticWidgetWallpaper = staticCompositionLocalOf<WallpaperLook?> { null }
internal val LocalWidgetWallpaper = staticCompositionLocalOf<WidgetWallpaper?> { null }

/** The colour under a widget's own surface, sampled at its centre when glass is on. */
internal data class WidgetWallpaper(val background: Int) {
    fun inkOn(surface: Color, opacity: Float = surface.alpha): Color =
        if (contentIsDarkOn(surface.toArgb(), opacity, background)) Color.Black else Color.White
}

val LocalTonalWidgets = staticCompositionLocalOf { false }

private const val WIDGET_VEIL = 0.30f

/** Only the three widgets on a veil enter this scope. The clock keeps its own backdrop and style. */
@Composable
internal fun WidgetOverWallpaper(modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    val look = LocalAutomaticWidgetWallpaper.current
    val backdrop = LocalHomeBackdrop.current
    var centerY by remember { mutableStateOf<Float?>(null) }
    val placed = if (look == null || backdrop.glassOpacity == null) modifier else modifier.onGloballyPositioned {
        centerY = it.positionInRoot().y + it.size.height / 2f
    }
    val wallpaper = look?.let { report ->
        // Without the hint a bare wallpaper can be dark even when its reported main colour is light.
        val background = if (backdrop.glassOpacity == null && !report.supportsDarkText) {
            Color.Black.toArgb()
        } else {
            backdrop.colorBehind(centerY, report.color ?: report.underGlass(0f))
        }
        WidgetWallpaper(background)
    }
    CompositionLocalProvider(LocalWidgetWallpaper provides wallpaper) { content(placed) }
}

@Composable
internal fun widgetContentColor(): Color {
    if (LocalTonalWidgets.current) return MaterialTheme.colorScheme.onSurface
    return LocalWidgetWallpaper.current?.inkOn(Color.Black, WIDGET_VEIL) ?: Color.White
}

@Composable
internal fun widgetTextShadow(ink: Color): Shadow? =
    if (LocalWidgetWallpaper.current != null && ink == Color.Black) null
    else Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f)

@Composable
internal fun widgetSurfaceColor(): Color =
    if (LocalTonalWidgets.current) MaterialTheme.colorScheme.surfaceContainer else Color.Black.copy(alpha = WIDGET_VEIL)

@Composable
internal fun WidgetSurface(
    modifier: Modifier = Modifier,
    color: Color = widgetSurfaceColor(),
    contentColor: Color = widgetContentColor(),
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = color, contentColor = contentColor) {
        Box(content = content)
    }
}
