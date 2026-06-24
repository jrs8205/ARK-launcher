package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Drop-target bar shown at the top while an icon is dragged — the Compose adaptation of Launcher3's
 * `DropTargetBar` (`DeleteDropTarget` + `SecondaryDropTarget`). Each pill publishes its bounds to
 * [controller]; the source surface hit-tests the drop against them (see [HomeDragController.barActionAt]).
 */
@Composable
fun DragDropBar(controller: HomeDragController, modifier: Modifier = Modifier) {
    // Recomputes per frame but only recomposes the pills when the hovered action changes.
    val activeAction by remember { derivedStateOf { controller.barActionAt(controller.rootPosition) } }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropPill(
            label = stringResource(R.string.drop_remove),
            active = activeAction == DropAction.Remove,
            onBounds = { controller.removeBounds = it },
        )
        DropPill(
            label = stringResource(R.string.drop_info),
            active = activeAction == DropAction.Info,
            onBounds = { controller.infoBounds = it },
        )
        DropPill(
            label = stringResource(R.string.drop_uninstall),
            active = activeAction == DropAction.Uninstall,
            onBounds = { controller.uninstallBounds = it },
        )
    }
}

@Composable
private fun DropPill(label: String, active: Boolean, onBounds: (Rect) -> Unit) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
        modifier = Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) },
    ) {
        Text(
            text = label,
            color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
