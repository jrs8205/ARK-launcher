package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Home screen. The system status bar is used as-is. Whole-screen vertical gestures: swipe up
 * opens the app drawer, swipe down opens the notification shade. Widgets and app shortcuts will
 * fill this area in later milestones.
 */
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var triggered = false
                detectVerticalDragGestures(
                    onDragStart = { triggered = false },
                    onDragEnd = { triggered = false },
                    onVerticalDrag = { _, dragAmount ->
                        if (!triggered) {
                            if (dragAmount < -30f) {
                                triggered = true
                                onOpenDrawer()
                            } else if (dragAmount > 30f) {
                                triggered = true
                                NotificationShade.expand(context)
                            }
                        }
                    },
                )
            },
    ) {
        Text(
            text = stringResource(R.string.home_open_drawer_hint),
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}
