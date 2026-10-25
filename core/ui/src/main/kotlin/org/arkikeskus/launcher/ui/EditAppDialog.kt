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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.ui.component.AppIcon

/**
 * "Edit" on an app, as in Lawnchair and Nova Launcher: its icon — tap it to pick another (see
 * [IconPickerDialog]) — and its name. [onSave] gets the name and the icon (null = the app's own);
 * [onReset] brings back both of the app's own. A gallery picture imported here but not saved is
 * deleted again.
 */
@Composable
fun EditAppDialog(
    app: AppItem,
    onSave: (name: String, icon: CustomIcon?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = LocalIconChoices.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(app.key) { mutableStateOf(app.label) }
    var icon by remember(app.key) { mutableStateOf(app.customIcon) }
    var picking by remember { mutableStateOf(false) }
    val imported = remember { mutableStateListOf<CustomIcon>() }
    fun leave(kept: CustomIcon?) {
        val unused = imported.filter { it != kept }
        if (choices != null && unused.isNotEmpty()) scope.launch(Dispatchers.IO) { unused.forEach(choices::discard) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { leave(app.customIcon) },
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
                    AppIcon(appItem = app.copy(customIcon = icon), labelColor = Color.Transparent, showLabel = false, iconSize = 64.dp)
                }
                Text(
                    stringResource(R.string.edit_icon_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rename_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name, icon)
                leave(icon)
            }) { Text(stringResource(R.string.rename_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    onReset()
                    leave(null)
                }) { Text(stringResource(R.string.rename_reset)) }
                TextButton(onClick = { leave(app.customIcon) }) { Text(stringResource(R.string.rename_cancel)) }
            }
        },
    )
    if (picking) {
        IconPickerDialog(
            app = app,
            original = { size -> AppIcon(appItem = app.copy(customIcon = null), labelColor = Color.Transparent, showLabel = false, iconSize = size) },
            onPick = { picked ->
                if (picked is CustomIcon.FromFile && picked != app.customIcon) imported += picked
                icon = picked
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * A folder's icon beside its name in the open folder: [preview] draws it (null = the folder's preview
 * of its apps); a tap picks another, which [onPick] saves at once — like the name typed next to it.
 */
@Composable
fun FolderIconButton(icon: CustomIcon?, preview: @Composable (CustomIcon?, Dp) -> Unit, onPick: (CustomIcon?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val changeIcon = stringResource(R.string.icon_change)
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { picking = true }
            .semantics { contentDescription = changeIcon }
            .padding(4.dp),
    ) {
        preview(icon, 48.dp)
    }
    if (picking) {
        IconPickerDialog(
            app = null,
            original = { size -> preview(null, size) },
            onPick = { picked ->
                picking = false
                onPick(picked)
            },
            onDismiss = { picking = false },
        )
    }
}
