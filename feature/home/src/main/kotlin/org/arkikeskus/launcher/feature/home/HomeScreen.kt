package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Home screen. Whole-screen gestures (toggleable in settings): swipe up = app drawer,
 * swipe down = notification shade, long-press = launcher settings. Shows the dock at the bottom.
 */
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(settings.swipeUpForDrawer, settings.swipeDownForNotifications) {
                var triggered = false
                detectVerticalDragGestures(
                    onDragStart = { triggered = false },
                    onDragEnd = { triggered = false },
                    onVerticalDrag = { _, dragAmount ->
                        if (!triggered) {
                            if (dragAmount < -30f && settings.swipeUpForDrawer) {
                                triggered = true
                                onOpenDrawer()
                            } else if (dragAmount > 30f && settings.swipeDownForNotifications) {
                                triggered = true
                                NotificationShade.expand(context)
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onOpenSettings() })
            },
    ) {
        if (settings.dockEnabled && uiState.dockApps.isNotEmpty()) {
            Dock(
                apps = uiState.dockApps,
                onAppClick = viewModel::launch,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
        } else {
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
}
