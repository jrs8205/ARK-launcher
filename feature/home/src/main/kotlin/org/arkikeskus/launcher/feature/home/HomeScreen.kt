package org.arkikeskus.launcher.feature.home

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.component.AppIcon
import kotlin.math.roundToInt

/**
 * Home screen: a paged [Workspace] of app shortcuts + the dock. All home gestures (icon drag, swipe
 * up/down, long-press settings, page swipe) live inside [Workspace] so they don't fight each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    homeSignals: Flow<Unit> = emptyFlow(),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val context = LocalContext.current
    val density = LocalDensity.current
    var selectedHomeApp by remember { mutableStateOf<AppItem?>(null) }
    var selectedDockApp by remember { mutableStateOf<AppItem?>(null) }

    // Shared drag state spanning the workspace and the dock (Launcher3-style drag layer/controller).
    val dragController = rememberHomeDragController()
    SideEffect {
        dragController.dockItemCount = uiState.dockApps.size
        dragController.dockHasSpace = settings.dockEnabled && uiState.dockApps.size < settings.dockColumns
    }
    // The floating icon is positioned in root coords; subtract this Box's origin to place it locally.
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { screenOrigin = it.positionInRoot() },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Workspace(
                pageCount = uiState.pageCount,
                columns = settings.homeColumns,
                rows = viewModel.rows,
                placedApps = uiState.placedApps,
                showLabels = settings.showHomeLabels,
                showPageIndicator = settings.showPageIndicator,
                swipeUpForDrawer = settings.swipeUpForDrawer,
                swipeDownForNotifications = settings.swipeDownForNotifications,
                homeSignals = homeSignals,
                dragController = dragController,
                onAppClick = viewModel::launch,
                onAppMenu = { selectedHomeApp = it },
                onMove = viewModel::moveItem,
                onMoveToDock = { app, index -> viewModel.moveToDock(app, index) },
                onDropOnBar = { app, action -> handleBarDrop(context, viewModel, dragController, app, action) },
                onOpenDrawer = onOpenDrawer,
                onOpenNotifications = { NotificationShade.expand(context) },
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )

            if (settings.dockEnabled && uiState.dockApps.isNotEmpty()) {
                Dock(
                    apps = uiState.dockApps,
                    showLabels = settings.showDockLabels,
                    backgroundAlpha = settings.dockBackgroundOpacity,
                    dragController = dragController,
                    onAppClick = viewModel::launch,
                    onReorder = viewModel::reorderDock,
                    onMoveToHome = { app, page, cellX, cellY -> viewModel.moveToHome(app, page, cellX, cellY) },
                    onDropOnBar = { app, action -> handleBarDrop(context, viewModel, dragController, app, action) },
                    onAppMenu = { selectedDockApp = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }

        // Drop-target bar at the top while dragging (remove / app info / uninstall zones).
        if (dragController.isDragging) {
            DragDropBar(
                controller = dragController,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 12.dp, end = 12.dp),
            )
        }

        // Single floating icon for any in-progress drag, drawn above both surfaces so it can travel
        // between them. Positioned by the finger's root coords (minus this Box's origin).
        val dragged = dragController.draggedApp
        if (dragged != null) {
            val sizeDp = 56.dp
            val halfPx = with(density) { (sizeDp / 2).toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragController.rootPosition.x - screenOrigin.x - halfPx).roundToInt(),
                            (dragController.rootPosition.y - screenOrigin.y - halfPx).roundToInt(),
                        )
                    }
                    .size(sizeDp)
                    .graphicsLayer {
                        alpha = 0.92f
                        scaleX = 1.1f
                        scaleY = 1.1f
                    },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    appItem = dragged,
                    labelColor = Color.White,
                    showLabel = false,
                    iconSize = 52.dp,
                )
            }
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
                    AppActions.openAppInfo(context, selected)
                    selectedHomeApp = null
                }
                HomeActionRow(stringResource(R.string.uninstall)) {
                    AppActions.uninstall(context, selected)
                    selectedHomeApp = null
                }
            }
        }
    }

    val dockSelected = selectedDockApp
    if (dockSelected != null) {
        ModalBottomSheet(onDismissRequest = { selectedDockApp = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = dockSelected.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HomeActionRow(stringResource(R.string.dock_remove)) {
                    viewModel.removeFromDock(dockSelected)
                    selectedDockApp = null
                }
                HomeActionRow(stringResource(R.string.home_add)) {
                    viewModel.addToHome(dockSelected)
                    selectedDockApp = null
                }
                HomeActionRow(stringResource(R.string.app_info)) {
                    AppActions.openAppInfo(context, dockSelected)
                    selectedDockApp = null
                }
                HomeActionRow(stringResource(R.string.uninstall)) {
                    AppActions.uninstall(context, dockSelected)
                    selectedDockApp = null
                }
            }
        }
    }
}

/** Runs a drop-target-bar action against [app], targeting the surface it was dragged from. */
private fun handleBarDrop(
    context: Context,
    viewModel: HomeViewModel,
    controller: HomeDragController,
    app: AppItem,
    action: DropAction,
) {
    when (action) {
        DropAction.Remove ->
            if (controller.source == DragSource.Dock) viewModel.removeFromDock(app) else viewModel.removeFromHome(app)
        DropAction.Info -> AppActions.openAppInfo(context, app)
        DropAction.Uninstall -> AppActions.uninstall(context, app)
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
