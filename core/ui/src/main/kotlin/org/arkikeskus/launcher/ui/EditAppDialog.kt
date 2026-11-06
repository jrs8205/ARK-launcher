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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.ui.component.AppIcon

/**
 * "Edit" on an app, as in Lawnchair and Nova Launcher: its icon — tap it to pick another (see
 * [IconPickerDialog]) — and its name. [onSave] gets the name, or null when it was left as it was (so
 * picking only an icon never pins the app's current name over a later one of its own), and the icon
 * (null = the app's own); [onReset] brings back both of the app's own. A gallery picture imported
 * here but not saved is deleted again when the dialog goes — by its buttons or from outside (HOME).
 * With [swipeUp] (home's dialog) a "Swipe up opens" row follows the name: the app a swipe up on this
 * icon opens, picked from the other apps and saved at once — Save and Reset leave it alone.
 */
@Composable
fun EditAppDialog(
    app: AppItem,
    onSave: (name: String?, icon: CustomIcon?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    swipeUp: SwipeUpChoice? = null,
) {
    val choices by rememberUpdatedState(LocalIconChoices.current)
    var name by rememberSaveable(app.key) { mutableStateOf(app.label) }
    var icon by remember(app.key) { mutableStateOf(app.customIcon) }
    var picking by remember { mutableStateOf(false) }
    val imported = remember { mutableStateListOf<CustomIcon>() }
    var kept by remember { mutableStateOf(app.customIcon) }
    // On leaving the composition, not in a button's handler: the home screen also takes the dialog
    // away without a word to it (HOME pressed), and a scope of the dialog's own would die with it.
    DisposableEffect(Unit) {
        onDispose { choices?.let { c -> imported.filter { it != kept }.forEach(c::discard) } }
    }
    fun leave(keep: CustomIcon?) {
        kept = keep
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
                if (swipeUp != null) SwipeUpRow(app, swipeUp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name.takeIf { it.trim() != app.label }, icon)
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
 * The "Swipe up opens" row of [EditAppDialog]: [target] is the app a swipe up on the edited icon opens
 * today (null = none, the usual swipe), [apps] every app to choose from (the edited one is left out),
 * and [onPick] gets the choice — an app, or null for none — to save at once.
 */
class SwipeUpChoice(val target: AppItem?, val apps: List<AppItem>, val onPick: (AppItem?) -> Unit)

@Composable
private fun SwipeUpRow(app: AppItem, choice: SwipeUpChoice) {
    // Shows the pick at once; a change from outside (the saved choice arriving) resets it to that.
    var target by remember(app.key, choice.target) { mutableStateOf(choice.target) }
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { picking = true }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.edit_swipe_up), style = MaterialTheme.typography.bodyMedium)
            Text(
                target?.label ?: stringResource(R.string.edit_swipe_up_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        target?.let { AppIcon(appItem = it, labelColor = Color.Transparent, showLabel = false, iconSize = 32.dp) }
    }
    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.edit_swipe_up),
            apps = choice.apps.filter { it.key != app.key },
            noneLabel = stringResource(R.string.edit_swipe_up_none_option),
            onPick = { picked ->
                target = picked
                picking = false
                choice.onPick(picked)
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
