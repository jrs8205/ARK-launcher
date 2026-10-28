package org.arkikeskus.launcher.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.ExpressivePalette
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

internal const val FOLDER_MANAGER_FOLDER_ROW_TAG = "folder-manager-folder"
internal const val FOLDER_MANAGER_APP_ROW_TAG = "folder-manager-app"
internal const val FOLDER_MANAGER_SEARCH_TAG = "folder-manager-search"
internal const val FOLDER_MANAGER_NAME_TAG = "folder-manager-name"

/**
 * Settings → App drawer → Folders (issue #17): every drawer folder with its app count, and a
 * folder's page — its name, a search field and the apps it could hold with a switch each (its own
 * members on; an app in another folder is not offered, an app is in one folder at most; hidden
 * apps are not offered either), and Delete, asked first. Back leaves a page for the list, the list
 * for the settings.
 */
@Composable
internal fun DrawerFoldersManager(
    /** Null while the folders are still loading: a page restored after process death waits for them. */
    folders: List<DrawerFolder>?,
    apps: List<AppItem>,
    hiddenKeys: Set<String>,
    onCreate: () -> Unit,
    onRename: (id: Long, name: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onSetMember: (id: Long, key: String, member: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalExpressivePalette.current
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = openId?.let { id -> folders?.firstOrNull { it.id == id } }
    // A folder deleted elsewhere (a restore, the drawer) takes its page with it - once the list is
    // known: the fresh process after process death has no folders yet, not none.
    LaunchedEffect(openId, folders) {
        if (openId != null && folders != null && open == null) openId = null
    }
    BackHandler {
        if (openId != null) openId = null else onBack()
    }
    Surface(modifier = modifier, color = p.bg) {
        if (folders == null) {
            // Still loading: nothing to list, and no page to close.
        } else if (open != null) {
            val otherMembers = remember(folders, open.id) {
                folders.filter { it.id != open.id }.flatMapTo(HashSet()) { it.appKeys }
            }
            FolderPage(
                folder = open,
                apps = apps,
                hiddenKeys = hiddenKeys,
                otherMembers = otherMembers,
                onRename = { onRename(open.id, it) },
                onDelete = {
                    onDelete(open.id)
                    openId = null
                },
                onSetMember = { key, member -> onSetMember(open.id, key, member) },
            )
        } else {
            FolderList(folders = folders, apps = apps, onOpen = { openId = it }, onCreate = onCreate)
        }
    }
}

@Composable
private fun FolderList(
    folders: List<DrawerFolder>,
    apps: List<AppItem>,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
) {
    val p = LocalExpressivePalette.current
    // A member whose app was uninstalled lingers in the folder's keys; count what the user can see.
    val installed = remember(apps) { apps.mapTo(HashSet()) { it.key } }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Text(
            text = stringResource(R.string.settings_drawer_folders),
            color = p.text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
        )
        if (folders.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_drawer_folders_empty),
                color = p.dim,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items = folders, key = { it.id }) { folder ->
                val count = folder.appKeys.count { it in installed }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(folder.id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .testTag(FOLDER_MANAGER_FOLDER_ROW_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = folder.name, color = p.text, fontSize = 16.sp)
                        Text(
                            text = pluralStringResource(R.plurals.settings_folder_apps_count, count, count),
                            color = p.dim,
                            fontSize = 13.sp,
                        )
                    }
                    Icon(painterResource(LauncherIcons.ChevronRight), contentDescription = null, tint = p.dim)
                }
            }
        }
        Button(onClick = onCreate, modifier = Modifier.padding(20.dp)) {
            Text(stringResource(R.string.settings_new_drawer_folder))
        }
    }
}

@Composable
private fun FolderPage(
    folder: DrawerFolder,
    apps: List<AppItem>,
    hiddenKeys: Set<String>,
    otherMembers: Set<String>,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onSetMember: (key: String, member: Boolean) -> Unit,
) {
    val p = LocalExpressivePalette.current
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    var query by rememberSaveable(folder.id) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val members = remember(folder.appKeys) { folder.appKeys.toSet() }
    val offered = remember(apps, hiddenKeys, otherMembers, members, query) {
        val needle = query.trim()
        apps.filter { app ->
            app.key !in hiddenKeys &&
                (app.key in members || app.key !in otherMembers) &&
                (needle.isEmpty() || app.label.contains(needle, ignoreCase = true))
        }
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                onRename(it)
            },
            singleLine = true,
            label = { Text(stringResource(R.string.settings_folder_name)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                .testTag(FOLDER_MANAGER_NAME_TAG),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.settings_folder_search)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag(FOLDER_MANAGER_SEARCH_TAG),
        )
        if (offered.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_folder_no_results),
                color = p.dim,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items = offered, key = { it.key }) { app ->
                val member = app.key in members
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = member, role = Role.Switch, onValueChange = { onSetMember(app.key, it) })
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .testTag(FOLDER_MANAGER_APP_ROW_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(appItem = app, labelColor = p.text, showLabel = false, iconSize = 40.dp)
                    Text(
                        text = app.label,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        color = p.text,
                        fontSize = 16.sp,
                    )
                    Switch(checked = member, onCheckedChange = null, colors = expressiveSwitchColors(p))
                }
            }
        }
        TextButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.settings_folder_delete)) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.settings_folder_delete_title)) },
            text = { Text(stringResource(R.string.settings_folder_delete_text, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.settings_folder_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.settings_folder_cancel)) }
            },
        )
    }
}

@Composable
private fun expressiveSwitchColors(p: ExpressivePalette) = SwitchDefaults.colors(
    checkedTrackColor = Accent,
    checkedThumbColor = Color.White,
    checkedBorderColor = Accent,
    uncheckedTrackColor = p.trackOff,
    uncheckedThumbColor = p.thumbOff,
    uncheckedBorderColor = p.trackOff,
)
