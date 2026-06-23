package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.designsystem.theme.LocalLauncherColors
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon

/** Bottom dock: a translucent rounded bar of favorite app icons. */
@Composable
fun Dock(
    apps: List<AppItem>,
    onAppClick: (AppItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcherColors = LocalLauncherColors.current
    Surface(
        modifier = modifier,
        color = launcherColors.dockScrim,
        shape = RoundedCornerShape(30.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            apps.forEach { app ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onAppClick(app) },
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(
                        appItem = app,
                        labelColor = Color.White,
                        showLabel = false,
                        iconSize = 52.dp,
                    )
                }
            }
        }
    }
}
