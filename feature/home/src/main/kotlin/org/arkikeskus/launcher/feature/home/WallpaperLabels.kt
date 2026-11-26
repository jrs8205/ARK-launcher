package org.arkikeskus.launcher.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.WallpaperLook
import org.arkikeskus.launcher.ui.component.LocalAppLabelShadow

internal fun homeLabelColor(settings: LauncherSettings, wallpaper: WallpaperLook, tint: Float, plate: Float = 0f): Color =
    if (!settings.automaticHomeLabels) Color(settings.appLabelColor)
    else if (wallpaper.darkHomeText(tint, plate)) Color.Black else Color.White

@Composable
internal fun WallpaperLabels(ink: Color, automatic: Boolean, content: @Composable () -> Unit) {
    val shadow = if (automatic) with(LocalDensity.current) {
        val opposite = if (ink == Color.Black) Color.White else Color.Black
        Shadow(opposite.copy(alpha = 0.65f), Offset(0f, 0.5.dp.toPx()), 1.dp.toPx())
    } else null
    CompositionLocalProvider(LocalAppLabelShadow provides shadow, content = content)
}
