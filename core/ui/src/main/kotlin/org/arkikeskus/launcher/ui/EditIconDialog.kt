package org.arkikeskus.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.ui.component.PickedIcon

/**
 * The icon editor of something that has no name to edit: a pinned shortcut (issue #23, a browser's
 * web shortcut dressed from an icon pack). The Edit dialog's icon half: the icon as it would show
 * ([icon] picked, else [original] as the app pinned it) with a tap to the picker, [label] under it,
 * Save → [onSave] with the picked icon (null = the original), Reset → [onReset], Cancel.
 *
 * Imported-but-unsaved pictures are discarded on leaving the composition, as in [EditAppDialog]: the
 * home screen also takes the dialog away without a word to it (HOME pressed).
 */
@Composable
fun EditIconDialog(
    label: String,
    icon: CustomIcon?,
    original: @Composable (Dp) -> Unit,
    onSave: (CustomIcon?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choices by rememberUpdatedState(LocalIconChoices.current)
    var picked by remember(label, icon) { mutableStateOf(icon) }
    var picking by remember { mutableStateOf(false) }
    val imported = remember { mutableStateListOf<CustomIcon>() }
    var kept by remember { mutableStateOf(icon) }
    DisposableEffect(Unit) {
        onDispose { choices?.let { c -> imported.filter { it != kept }.forEach(c::discard) } }
    }
    fun leave(keep: CustomIcon?) {
        kept = keep
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { leave(icon) },
        title = { Text(stringResource(R.string.edit_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val changeIcon = stringResource(R.string.icon_change)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { picking = true }
                        .semantics { contentDescription = changeIcon }
                        .padding(8.dp),
                ) {
                    val shown = picked
                    if (shown != null) {
                        PickedIcon(icon = shown, size = 64.dp, contentDescription = null, fallback = { original(64.dp) })
                    } else {
                        original(64.dp)
                    }
                }
                Text(
                    stringResource(R.string.edit_icon_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(picked)
                leave(picked)
            }) { Text(stringResource(R.string.rename_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    onReset()
                    leave(null)
                }) { Text(stringResource(R.string.rename_reset)) }
                TextButton(onClick = { leave(icon) }) { Text(stringResource(R.string.rename_cancel)) }
            }
        },
    )
    if (picking) {
        IconPickerDialog(
            app = null,
            original = original,
            onPick = { chosen ->
                if (chosen is CustomIcon.FromFile && chosen != icon) imported += chosen
                picked = chosen
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}
