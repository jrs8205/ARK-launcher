package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.SearchResult
import org.arkikeskus.launcher.ui.AppActionPopup
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.AppShortcuts
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.PopupAction
import org.arkikeskus.launcher.ui.RenameDialog
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.ContactAvatar
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.arkikeskus.launcher.ui.component.LocalThemedIcons
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow
import org.arkikeskus.launcher.ui.expressive.ExpressiveCard
import org.arkikeskus.launcher.ui.expressive.ExpressiveSectionTitle
import org.arkikeskus.launcher.ui.expressive.ExpressiveTheme
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.rememberHomeDragController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerScreen(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onDrawerDrag: (Float) -> Unit = {},
    onDrawerSettle: (Float) -> Unit = {},
    dragController: HomeDragController = rememberHomeDragController(),
    onDragOutStart: () -> Unit = {},
    homeSignals: Flow<Unit> = emptyFlow(),
    viewModel: AppDrawerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuTarget by remember { mutableStateOf<Pair<AppItem, Rect>?>(null) }
    var renameTarget by remember { mutableStateOf<AppItem?>(null) }
    var openFolderId by remember { mutableStateOf<Long?>(null) }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val focusManager = LocalFocusManager.current

    // HOME pressed → dismiss the popup, drop search focus (which hides the soft keyboard) and reset the
    // query, so the keyboard never lingers after the drawer slides away and the drawer reopens fresh.
    LaunchedEffect(homeSignals) {
        homeSignals.collect {
            menuTarget = null
            focusManager.clearFocus()
            viewModel.onQueryChange("")
        }
    }

    val badges = if (uiState.showNotificationDots) uiState.badges else emptyMap()

    // All app icons in the drawer (grid, folder sheet, popup) honour the themed-icons setting and the
    // user's app-label text-size multiplier.
    CompositionLocalProvider(
        LocalThemedIcons provides uiState.useThemedIcons,
        LocalAppLabelScale provides uiState.appLabelTextScale,
    ) {
    ExpressiveTheme {
    AppDrawerContent(
        apps = uiState.apps,
        frequentApps = uiState.frequentApps,
        folders = uiState.folders,
        query = uiState.query,
        columns = uiState.columns,
        badges = badges,
        badgeShowCount = uiState.notificationDotCount,
        badgeScale = uiState.notificationDotScale,
        showSearch = uiState.showSearch,
        onQueryChange = viewModel::onQueryChange,
        onFolderClick = { openFolderId = it.id },
        onAppClick = { app ->
            if (app.packageName == context.packageName) {
                onOpenSettings()
            } else if (viewModel.onAppClick(app)) {
                // Keep the drawer open if the launch failed, so the user isn't dumped back to home
                // with no explanation.
                onClose()
            }
        },
        onAppLongClick = { app, bounds -> menuTarget = app to bounds },
        onDrawerDrag = onDrawerDrag,
        onDrawerSettle = onDrawerSettle,
        dragController = dragController,
        onDragOutStart = onDragOutStart,
        onDropOnHome = { app, page, cellX, cellY -> viewModel.addToHomeAt(app, page, cellX, cellY) },
        onDropOnDock = { app -> viewModel.addToDock(app) },
        showLabels = uiState.showLabels,
        calc = uiState.calc,
        settingResults = uiState.settingResults,
        contactResults = uiState.contactResults,
        locked = uiState.desktopLocked,
        modifier = modifier,
    )

    val menu = menuTarget
    if (menu != null) {
        val (app, bounds) = menu
        val inHome = app.key in uiState.homeKeys
        val inDock = app.key in uiState.dockKeys
        // Anchor to the icon's edge (not its centre) so the popup clears it with a small gap: below
        // the cell for top-half icons, above it for bottom-half ones.
        val preferAbove = bounds.center.y > windowHeightPx / 2
        val anchorY = (if (preferAbove) bounds.top else bounds.bottom).roundToInt()
        val anchor = IntOffset(bounds.center.x.roundToInt(), anchorY)
        AppActionPopup(
            app = app,
            anchor = anchor,
            preferAbove = preferAbove,
            actions = listOf(
                PopupAction(stringResource(R.string.app_info), LauncherIcons.Info) { AppActions.openAppInfo(context, app) },
                PopupAction(stringResource(R.string.rename), LauncherIcons.Edit) { renameTarget = app },
                PopupAction(
                    stringResource(if (inHome) R.string.remove_from_home else R.string.add_to_home),
                    if (inHome) LauncherIcons.Close else LauncherIcons.Add,
                ) {
                    if (inHome) viewModel.removeFromHome(app) else viewModel.addToHome(app)
                },
                PopupAction(
                    stringResource(if (inDock) R.string.remove_from_dock else R.string.add_to_dock),
                    if (inDock) LauncherIcons.Close else LauncherIcons.Add,
                ) {
                    if (inDock) viewModel.removeFromDock(app) else viewModel.addToDock(app)
                },
                PopupAction(stringResource(R.string.hide_app), LauncherIcons.VisibilityOff) { viewModel.hideApp(app) },
                PopupAction(stringResource(R.string.uninstall), LauncherIcons.Delete) { AppActions.uninstall(context, app) },
            ),
            onDismiss = { menuTarget = null },
            onPinShortcut = { item ->
                AppShortcuts.pin(context, item)
                viewModel.addPinnedShortcut(item.packageName, item.id, item.userSerial)
            },
        )
    }

    renameTarget?.let { app ->
        RenameDialog(
            initialName = app.label,
            onConfirm = { viewModel.setCustomLabel(app.key, it) },
            onReset = { viewModel.setCustomLabel(app.key, null) },
            onDismiss = { renameTarget = null },
        )
    }

    // Close the open folder sheet if its folder was deleted (or all members removed and it's gone).
    LaunchedEffect(openFolderId, uiState.folders) {
        if (openFolderId != null && uiState.folders.none { it.id == openFolderId }) openFolderId = null
    }
    val openFolder = openFolderId?.let { id -> uiState.folders.firstOrNull { it.id == id } }
    if (openFolder != null) {
        DrawerFolderSheet(
            folder = openFolder,
            candidateApps = uiState.apps,
            badges = badges,
            badgeShowCount = uiState.notificationDotCount,
            badgeScale = uiState.notificationDotScale,
            onRename = { viewModel.renameDrawerFolder(openFolder.id, it) },
            onAppClick = { app ->
                if (viewModel.onAppClick(app)) {
                    openFolderId = null
                    onClose()
                }
            },
            onRemoveApp = { viewModel.removeAppFromDrawerFolder(openFolder.id, it.key) },
            onAddApps = { keys -> viewModel.addAppsToDrawerFolder(openFolder.id, keys) },
            onDelete = {
                viewModel.deleteDrawerFolder(openFolder.id)
                openFolderId = null
            },
            onDismiss = { openFolderId = null },
        )
    }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppDrawerContent(
    apps: List<AppItem>,
    frequentApps: List<AppItem>,
    folders: List<DrawerFolderUi>,
    query: String,
    columns: Int,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onFolderClick: (DrawerFolderUi) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem, Rect) -> Unit,
    onDrawerDrag: (Float) -> Unit,
    onDrawerSettle: (Float) -> Unit,
    dragController: HomeDragController,
    onDragOutStart: () -> Unit,
    onDropOnHome: (AppItem, Int, Int, Int) -> Unit,
    onDropOnDock: (AppItem) -> Unit,
    showLabels: Boolean,
    calc: SearchResult.Calculation?,
    settingResults: List<SearchResult.Setting>,
    contactResults: List<SearchResult.Contact>,
    locked: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
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

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Safety net: a pull-to-close that ends without a fling (e.g. a slow drag, finger
                // lifted) would otherwise never reach onPreFling, leaving `pulling` stuck true and the
                // drawer half-open. Reset here and settle on whatever velocity remains.
                if (pulling) {
                    pulling = false
                    onDrawerSettle(consumed.y + available.y)
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
                val searchPalette = LocalExpressivePalette.current
                // Version C: rounded pill, surfaceHi background, Accent cursor, no underline.
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.app_drawer_search_hint)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(painter = painterResource(LauncherIcons.Close), contentDescription = null, tint = Accent)
                            }
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = searchPalette.surfaceHi,
                        unfocusedContainerColor = searchPalette.surfaceHi,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Accent,
                        focusedTextColor = searchPalette.text,
                        unfocusedTextColor = searchPalette.text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            val context = LocalContext.current
            val searching = query.isNotBlank()
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                if (!searching) {
                    if (frequentApps.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "frequent" }) {
                            FrequentAppsCard(
                                apps = frequentApps,
                                columns = columns,
                                badges = badges,
                                badgeShowCount = badgeShowCount,
                                badgeScale = badgeScale,
                                showLabels = showLabels,
                                onAppClick = onAppClick,
                                onAppLongClick = onAppLongClick,
                            )
                        }
                    }
                    items(items = folders, key = { "folder-${it.id}" }, contentType = { "folder" }) { folder ->
                        DrawerFolderTile(folder = folder, showLabel = showLabels, onClick = { onFolderClick(folder) })
                    }
                    appCells(apps, badges, badgeShowCount, badgeScale, showLabels, onAppClick, onAppLongClick,
                        dragController, onDragOutStart, onDropOnHome, onDropOnDock, haptics, locked)
                } else {
                    calc?.let { c ->
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "calc" }) {
                            CalcResultCard(c) {
                                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                                clip?.setPrimaryClip(android.content.ClipData.newPlainText("result", c.result))
                                android.widget.Toast.makeText(
                                    context, context.getString(R.string.search_calc_copied),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    if (apps.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "header" }) {
                            ExpressiveSectionTitle(stringResource(R.string.search_section_apps))
                        }
                        appCells(apps, badges, badgeShowCount, badgeScale, showLabels, onAppClick, onAppLongClick,
                            dragController, onDragOutStart, onDropOnHome, onDropOnDock, haptics, locked)
                    }
                    if (settingResults.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "header" }) {
                            ExpressiveSectionTitle(stringResource(R.string.search_section_settings))
                        }
                        items(items = settingResults, key = { it.id }, span = { GridItemSpan(maxLineSpan) },
                            contentType = { "setting" }) { setting ->
                            ExpressiveActionRow(label = setting.title, description = "") {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(setting.action)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        }
                    }
                    if (contactResults.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "header" }) {
                            ExpressiveSectionTitle(stringResource(R.string.search_section_contacts))
                        }
                        items(items = contactResults, key = { it.id }, span = { GridItemSpan(maxLineSpan) },
                            contentType = { "contact" }) { contact ->
                            ContactResultRow(contact)
                        }
                    }
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

/** A drawer folder tile: a rounded 2×2 preview of its first apps + label; tap (or long-press, which
 *  also ticks the haptic like an app long-press) opens the folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerFolderTile(folder: DrawerFolderUi, showLabel: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    FolderSlot(folder.apps.getOrNull(0)); FolderSlot(folder.apps.getOrNull(1))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    FolderSlot(folder.apps.getOrNull(2)); FolderSlot(folder.apps.getOrNull(3))
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = folder.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FolderSlot(app: AppItem?) {
    if (app == null) {
        Spacer(Modifier.size(18.dp))
    } else {
        AppIcon(appItem = app, labelColor = Color.Transparent, showLabel = false, iconSize = 18.dp)
    }
}

/** The open-folder sheet: rename, member apps (tap launches, long-press removes), add apps, delete. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DrawerFolderSheet(
    folder: DrawerFolderUi,
    candidateApps: List<AppItem>,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    onRename: (String) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onRemoveApp: (AppItem) -> Unit,
    onAddApps: (List<String>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            var name by remember(folder.id) { mutableStateOf(folder.name) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onRename(it) },
                singleLine = true,
                label = { Text(stringResource(R.string.folder_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (folder.apps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.folder_remove_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                ) {
                    items(items = folder.apps, key = { it.key }) { app ->
                        AppIcon(
                            appItem = app,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            showLabel = true,
                            maxLabelLines = 2,
                            badgeCount = badges[app.badgeKey] ?: 0,
                            badgeShowCount = badgeShowCount,
                            badgeScale = badgeScale,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { onAppClick(app) },
                                    onLongClick = { onRemoveApp(app) },
                                )
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = true }) { Text(stringResource(R.string.folder_add_apps)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.folder_delete)) }
            }
        }
    }
    if (showAdd) {
        AddAppsDialog(
            apps = candidateApps,
            onConfirm = {
                onAddApps(it)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
}

/** Multi-select picker of apps to add to a drawer folder. */
@Composable
private fun AddAppsDialog(apps: List<AppItem>, onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_add_apps)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                apps.forEach { app ->
                    val checked = app.key in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (checked) selected.remove(app.key) else selected.add(app.key) }
                            .padding(vertical = 6.dp),
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
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) { Text(stringResource(R.string.folder_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folder_cancel)) } },
    )
}

/** The "most used" apps in a rounded Version C card above the alphabetical grid. The same apps also
 *  stay in the A–Z list below; this card is just a distinct quick-access shortcut row. Icons span
 *  the drawer columns (left-aligned), so the row tracks the icon-count setting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrequentAppsCard(
    apps: List<AppItem>,
    columns: Int,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem, Rect) -> Unit,
) {
    val p = LocalExpressivePalette.current
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.drawer_frequent_apps),
                color = p.dim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                apps.forEach { app ->
                    var bounds by remember(app.key) { mutableStateOf(Rect.Zero) }
                    AppIcon(
                        appItem = app,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        showLabel = showLabels,
                        maxLabelLines = 2,
                        badgeCount = badges[app.badgeKey] ?: 0,
                        badgeShowCount = badgeShowCount,
                        badgeScale = badgeScale,
                        modifier = Modifier
                            .weight(1f)
                            .onGloballyPositioned { bounds = it.boundsInRoot() }
                            .combinedClickable(
                                onClick = { onAppClick(app) },
                                onLongClick = { onAppLongClick(app, bounds) },
                            )
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
                // Keep icons aligned to the leftmost columns when fewer than a full row are used.
                if (apps.size < columns) {
                    Spacer(Modifier.weight((columns - apps.size).toFloat()))
                }
            }
        }
    }
}

/** The drawer app-grid cell, shared by the idle and searching layouts. */
private fun LazyGridScope.appCells(
    apps: List<AppItem>,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem, Rect) -> Unit,
    dragController: HomeDragController,
    onDragOutStart: () -> Unit,
    onDropOnHome: (AppItem, Int, Int, Int) -> Unit,
    onDropOnDock: (AppItem) -> Unit,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    locked: Boolean,
) {
    items(items = apps, key = { it.key }, contentType = { "app" }) { app ->
        var bounds by remember { mutableStateOf(Rect.Zero) }
        AppIcon(
            appItem = app,
            labelColor = MaterialTheme.colorScheme.onSurface,
            showLabel = showLabels,
            maxLabelLines = 2,
            badgeCount = badges[app.badgeKey] ?: 0,
            badgeShowCount = badgeShowCount,
            badgeScale = badgeScale,
            modifier = Modifier
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                // One unified gesture (like Workspace/Dock) but non-consuming until the
                // long-press fires, so a quick drag still scrolls the grid: quick tap
                // launches; a still long-press lifts → drag out to home/dock, or with no
                // movement opens the long-press menu.
                .pointerInput(app.key, locked) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        // 0 = long-press (timed out still), 1 = tap, 2 = scroll/abandon.
                        var outcome = 0
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null) {
                                    outcome = 2
                                    return@withTimeoutOrNull
                                }
                                if (!c.pressed) {
                                    outcome = 1
                                    return@withTimeoutOrNull
                                }
                                // Don't consume: let the LazyGrid scroll a quick drag.
                                if (c.isConsumed ||
                                    (c.position - down.position).getDistance() > slop
                                ) {
                                    outcome = 2
                                    return@withTimeoutOrNull
                                }
                            }
                        }
                        when (outcome) {
                            1 -> {
                                onAppClick(app)
                                return@awaitEachGesture
                            }
                            2 -> return@awaitEachGesture
                        }
                        // Desktop locked: long-press does nothing (no menu, no drag-out); tap still launches.
                        if (locked) return@awaitEachGesture
                        // LONG PRESS → lift into the shared controller (drawer source).
                        // The finger's root position is the icon's [bounds] top-left
                        // (captured while the drawer is open) plus the pointer's local
                        // position. The drawer is hidden with alpha (not translated) during
                        // the drag, so its local coordinate space stays put and this stays
                        // accurate.
                        dragController.start(app, DragSource.Drawer, bounds.topLeft + down.position)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val completed = drag(down.id) { change ->
                            change.consume()
                            // Only commit to a drag-out once the finger has moved past the
                            // slop — otherwise a still long-press (with finger jitter) would
                            // collapse the drawer instead of just showing the menu.
                            if (!dragController.moving &&
                                (change.position - down.position).getDistance() > slop
                            ) {
                                dragController.beginMove()
                                onDragOutStart()
                            }
                            if (dragController.moving) {
                                dragController.update(bounds.topLeft + change.position)
                            }
                        }
                        if (completed && dragController.moving) {
                            val root = dragController.rootPosition
                            when {
                                dragController.isOverDock(root) && dragController.dockHasSpace ->
                                    onDropOnDock(app)
                                dragController.isOverGrid(root) -> {
                                    val (page, cx, cy) = dragController.cellAt(root)
                                    onDropOnHome(app, page, cx, cy)
                                }
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (completed) {
                            // No movement → static long-press → show the menu by the icon.
                            onAppLongClick(app, bounds)
                        }
                        dragController.stop()
                    }
                }
                .padding(vertical = 10.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun CalcResultCard(calc: SearchResult.Calculation, onCopy: () -> Unit) {
    val p = LocalExpressivePalette.current
    ExpressiveCard(onClick = onCopy) {
        Text("${calc.expression} = ", color = p.dim, fontSize = 18.sp)
        Text(calc.result, color = Accent, fontSize = 22.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun ContactResultRow(contact: SearchResult.Contact) {
    val context = LocalContext.current
    val p = LocalExpressivePalette.current
    val callDesc = stringResource(R.string.search_contact_call)
    val messageDesc = stringResource(R.string.search_contact_message)
    ExpressiveCard(onClick = {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(contact.lookupUri))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }) {
        // Avatar is decorative here — the contact name provides the accessible label.
        ContactAvatar(name = contact.name, photoUri = contact.photoUri)
        Text(contact.name, color = p.text, fontSize = 16.sp,
            modifier = Modifier.weight(1f).padding(start = 14.dp))
        if (contact.number != null) {
            IconButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_DIAL,
                            android.net.Uri.fromParts("tel", contact.number, null))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Icon(painter = painterResource(LauncherIcons.Call), contentDescription = callDesc, tint = Accent) }
            IconButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_SENDTO,
                            android.net.Uri.fromParts("smsto", contact.number, null))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Icon(painter = painterResource(LauncherIcons.Message), contentDescription = messageDesc, tint = Accent) }
        }
    }
}
