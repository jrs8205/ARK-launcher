package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.component.AppIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerScreen(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onDrawerDrag: (Float) -> Unit = {},
    onDrawerSettle: (Float) -> Unit = {},
    viewModel: AppDrawerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedApp by remember { mutableStateOf<AppItem?>(null) }

    val badges = if (uiState.showNotificationDots) uiState.badges else emptyMap()

    AppDrawerContent(
        apps = uiState.apps,
        query = uiState.query,
        columns = uiState.columns,
        badges = badges,
        badgeShowCount = uiState.notificationDotCount,
        badgeScale = uiState.notificationDotScale,
        showSearch = uiState.showSearch,
        onQueryChange = viewModel::onQueryChange,
        onAppClick = { app ->
            if (app.packageName == context.packageName) {
                onOpenSettings()
            } else if (viewModel.onAppClick(app)) {
                // Keep the drawer open if the launch failed, so the user isn't dumped back to home
                // with no explanation.
                onClose()
            }
        },
        onAppLongClick = { selectedApp = it },
        onDrawerDrag = onDrawerDrag,
        onDrawerSettle = onDrawerSettle,
        showLabels = uiState.showLabels,
        modifier = modifier,
    )

    val selected = selectedApp
    if (selected != null) {
        val inDock = selected.key in uiState.dockKeys
        ModalBottomSheet(onDismissRequest = { selectedApp = null }) {
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
                val inHome = selected.key in uiState.homeKeys
                ActionRow(
                    text = stringResource(
                        if (inHome) R.string.remove_from_home else R.string.add_to_home,
                    ),
                ) {
                    if (inHome) viewModel.removeFromHome(selected) else viewModel.addToHome(selected)
                    selectedApp = null
                }
                ActionRow(
                    text = stringResource(
                        if (inDock) R.string.remove_from_dock else R.string.add_to_dock,
                    ),
                ) {
                    if (inDock) viewModel.removeFromDock(selected) else viewModel.addToDock(selected)
                    selectedApp = null
                }
                ActionRow(stringResource(R.string.app_info)) {
                    AppActions.openAppInfo(context, selected)
                    selectedApp = null
                }
                ActionRow(stringResource(R.string.hide_app)) {
                    viewModel.hideApp(selected)
                    selectedApp = null
                }
                ActionRow(stringResource(R.string.uninstall)) {
                    AppActions.uninstall(context, selected)
                    selectedApp = null
                }
            }
        }
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppDrawerContent(
    apps: List<AppItem>,
    query: String,
    columns: Int,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem) -> Unit,
    onDrawerDrag: (Float) -> Unit,
    onDrawerSettle: (Float) -> Unit,
    showLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    // Finger-following pull-to-close: when the grid is at the top and the user keeps dragging down,
    // the leftover over-scroll reaches onPostScroll as a positive y — feed it to the shared drawer
    // progress so the drawer tracks the finger; the fling velocity settles it open/closed.
    val pullConnection = remember(onDrawerDrag, onDrawerSettle) {
        object : NestedScrollConnection {
            private var pulling = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // While pulling, an upward scroll re-opens the drawer rather than scrolling the list.
                if (pulling && available.y < 0f) {
                    onDrawerDrag(available.y)
                    return available
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) {
                    pulling = true
                    onDrawerDrag(available.y)
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulling) {
                    pulling = false
                    onDrawerSettle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .nestedScroll(pullConnection),
        ) {
            DragHandle()
            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.app_drawer_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                items(items = apps, key = { it.key }, contentType = { "app" }) { app ->
                    AppIcon(
                        appItem = app,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        showLabel = showLabels,
                        maxLabelLines = 2,
                        badgeCount = badges[app.badgeKey] ?: 0,
                        badgeShowCount = badgeShowCount,
                        badgeScale = badgeScale,
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { onAppClick(app) },
                                onLongClick = { onAppLongClick(app) },
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** Visual pull-down affordance: a small pill below the status bar. */
@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}
