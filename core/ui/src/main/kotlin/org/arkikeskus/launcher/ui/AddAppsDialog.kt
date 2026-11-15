package org.arkikeskus.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.component.AppIcon

/** Test tags of the Add-apps list: its search field and each app row it lists. */
const val ADD_APPS_SEARCH_TAG = "add-apps-search"
const val ADD_APPS_ROW_TAG = "add-apps-row"

/**
 * A list of [apps] to add to a folder, several at once: the app drawer's folders and the home screen's
 * (discussion #16) use the same one. A search field narrows the list by name (issue #17); the picks made
 * so far survive the narrowing. [onConfirm] gets the ticked apps in the order they were ticked. The words
 * are the caller's: each surface has them in its own resources.
 */
@Composable
fun AddAppsDialog(
    apps: List<AppItem>,
    title: String,
    searchHint: String,
    noResults: String,
    addLabel: String,
    cancelLabel: String,
    onConfirm: (List<AppItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        val needle = query.trim()
        if (needle.isEmpty()) apps else apps.filter { it.label.contains(needle, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(searchHint) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ADD_APPS_SEARCH_TAG),
                )
                if (shown.isEmpty()) {
                    Text(
                        text = noResults,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    shown.forEach { app ->
                        val checked = app.key in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (checked) selected.remove(app.key) else selected.add(app.key) }
                                .padding(vertical = 6.dp)
                                .testTag(ADD_APPS_ROW_TAG),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { if (it) selected.add(app.key) else selected.remove(app.key) },
                            )
                            AppIcon(appItem = app, labelColor = MaterialTheme.colorScheme.onSurface, showLabel = false, iconSize = 32.dp)
                            Text(
                                text = app.label,
                                modifier = Modifier.padding(start = 12.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val byKey = apps.associateBy { it.key }
                    onConfirm(selected.mapNotNull(byKey::get))
                },
                enabled = selected.isNotEmpty(),
            ) { Text(addLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}
