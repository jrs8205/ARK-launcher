package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppActions

/**
 * Home screen: a non-scrolling grid of placed app shortcuts (top) + the dock (bottom).
 * Whole-screen gestures (toggleable): swipe up = drawer, swipe down = notifications,
 * long-press empty area = settings. Long-press a home icon = remove it.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var selectedHomeApp by remember { mutableStateOf<AppItem?>(null) }

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
        if (uiState.homeApps.isNotEmpty()) {
            HomeGrid(
                apps = uiState.homeApps,
                columns = settings.homeColumns,
                showLabels = settings.showHomeLabels,
                onAppClick = viewModel::launch,
                onAppLongClick = { selectedHomeApp = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 24.dp),
            )
        }

        if (settings.dockEnabled && uiState.dockApps.isNotEmpty()) {
            Dock(
                apps = uiState.dockApps,
                showLabels = settings.showDockLabels,
                backgroundAlpha = settings.dockBackgroundOpacity,
                onAppClick = viewModel::launch,
                onReorder = viewModel::reorderDock,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
    }

    val selected = selectedHomeApp
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedHomeApp = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = selected.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HomeActionRow(stringResource(R.string.home_remove)) {
                    viewModel.removeFromHome(selected)
                    selectedHomeApp = null
                }
                HomeActionRow(stringResource(R.string.home_add_to_dock)) {
                    viewModel.addToDock(selected)
                    selectedHomeApp = null
                }
                HomeActionRow(stringResource(R.string.app_info)) {
                    AppActions.openAppInfo(context, selected.packageName)
                    selectedHomeApp = null
                }
                HomeActionRow(stringResource(R.string.uninstall)) {
                    AppActions.uninstall(context, selected.packageName)
                    selectedHomeApp = null
                }
            }
        }
    }
}

@Composable
private fun HomeActionRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
