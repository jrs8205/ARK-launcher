package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon

/** Non-scrolling top-aligned grid of app shortcuts placed on the home screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeGrid(
    apps: List<AppItem>,
    columns: Int,
    showLabels: Boolean,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        apps.chunked(columns).forEach { rowApps ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowApps.forEach { app ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = { onAppClick(app) },
                                onLongClick = { onAppLongClick(app) },
                            )
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            appItem = app,
                            labelColor = Color.White,
                            showLabel = showLabels,
                            iconSize = 52.dp,
                            maxLabelLines = 1,
                        )
                    }
                }
                repeat(columns - rowApps.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
