package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerScreen(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppDrawerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedApp by remember { mutableStateOf<AppItem?>(null) }

    AppDrawerContent(
        apps = uiState.apps,
        query = uiState.query,
        columns = uiState.columns,
        onQueryChange = viewModel::onQueryChange,
        onAppClick = { app ->
            if (app.packageName == context.packageName) {
                onOpenSettings()
            } else {
                viewModel.onAppClick(app)
                onClose()
            }
        },
        onAppLongClick = { selectedApp = it },
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
                ActionRow(
                    text = stringResource(
                        if (inDock) R.string.remove_from_dock else R.string.add_to_dock,
                    ),
                ) {
                    if (inDock) viewModel.removeFromDock(selected) else viewModel.addToDock(selected)
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
    onQueryChange: (String) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.app_drawer_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = apps, key = { it.key }, contentType = { "app" }) { app ->
                    AppIcon(
                        appItem = app,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        maxLabelLines = 2,
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
