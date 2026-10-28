package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.SearchResult
import org.arkikeskus.launcher.ui.AnchoredPopupCard
import org.arkikeskus.launcher.ui.AppActionPopup
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.AppShortcuts
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.DismissWhenDragMoves
import org.arkikeskus.launcher.ui.EditAppDialog
import org.arkikeskus.launcher.ui.FolderIconButton
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.IconPress
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.LocalAppLaunchSources
import org.arkikeskus.launcher.ui.LongPressDelay
import org.arkikeskus.launcher.ui.PopupAction
import org.arkikeskus.launcher.ui.R as UiR
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.PickedIcon
import org.arkikeskus.launcher.ui.component.ContactAvatar
import org.arkikeskus.launcher.ui.component.iconSizeForCell
import org.arkikeskus.launcher.ui.component.LocalAppLabelLines
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.arkikeskus.launcher.ui.component.LocalIconScale
import org.arkikeskus.launcher.ui.component.LocalIconPack
import org.arkikeskus.launcher.ui.component.LocalThemedIcons
import org.arkikeskus.launcher.ui.component.NotificationBadge
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
    homeSignals: Flow<Boolean> = emptyFlow(),
    drawerOpen: Boolean = true,
    /** The popup's Widgets row: the home screen shows the app's widgets (the drawer closes first). */
    onShowWidgets: ((AppItem) -> Unit)? = null,
    viewModel: AppDrawerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultFolderName = stringResource(UiR.string.folder_default_name)
    // What the ViewModel could not do for the user (e.g. the home screen is full), shown once each.
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            android.widget.Toast.makeText(context, notice.message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var menuTarget by remember { mutableStateOf<Pair<AppItem, Rect>?>(null) }
    // A tapped app opens with a zoom from its icon (AppLaunchSources).
    val launchSources = LocalAppLaunchSources.current
    // An icon's menu opens as the icon lifts; pulling the icon on closes it (issue #12).
    DismissWhenDragMoves(dragController) { menuTarget = null }
    var renameTarget by remember { mutableStateOf<AppItem?>(null) }
    // A tapped folder shows its apps in a card by the tile (id + the tile's root bounds); a
    // long-pressed one opens its editor. Two surfaces, so a tap can never change the folder.
    var folderCard by remember { mutableStateOf<Pair<Long, Rect>?>(null) }
    var editFolderId by remember { mutableStateOf<Long?>(null) }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val focusManager = LocalFocusManager.current
    // The drawer stays composed while closed (translated off-screen), so its grid scroll persists
    // across opens. When "open at top" is on, reset it to the top as the drawer closes (invisible),
    // so the next open shows the "most used" row / A–Z start instead of the last scroll position.
    val gridState = rememberLazyGridState()
    val workGridState = rememberLazyGridState()
    LaunchedEffect(drawerOpen, uiState.drawerOpensAtTop) {
        // Check the offset too: a short scroll leaves the first row visible (index still 0) but
        // offset > 0, and skipping the reset then would reopen the drawer slightly scrolled.
        // Both pages: the Work grid keeps its own scroll position.
        if (!drawerOpen && uiState.drawerOpensAtTop) {
            listOf(gridState, workGridState)
                .filter { it.firstVisibleItemIndex > 0 || it.firstVisibleItemScrollOffset > 0 }
                .forEach { it.scrollToItem(0) }
        }
    }
    // Any way of closing the drawer — a pull-down, Back, an app launched from the results — drops the
    // search focus (which hides the soft keyboard, otherwise left standing over the home screen) and
    // the query, so the drawer reopens on the A–Z list. Not during a drag-out: [drawerOpen] stays
    // true until the drag ends (the drawer-swipe rule), so a lift from the results keeps them.
    LaunchedEffect(drawerOpen) {
        if (!drawerOpen) {
            focusManager.clearFocus()
            viewModel.onQueryChange("")
        }
    }

    // HOME pressed → dismiss the popup and every window of the drawer's own (the Edit dialog with its
    // icon picker, a folder's card, a folder's sheet with its Add-apps dialog: separate windows that
    // the drawer sliding away would leave standing over the home screen), drop search focus (which
    // hides the soft keyboard) and reset the query, so the drawer reopens fresh.
    LaunchedEffect(homeSignals) {
        homeSignals.collect {
            menuTarget = null
            renameTarget = null
            folderCard = null
            editFolderId = null
            focusManager.clearFocus()
            viewModel.onQueryChange("")
        }
    }

    // Turning the drawer search OFF must clear any active query — the search field (and its clear
    // button) disappear, so an old filter would otherwise strand the grid with no way to reset it.
    LaunchedEffect(uiState.showSearch) {
        if (!uiState.showSearch && uiState.query.isNotBlank()) viewModel.onQueryChange("")
    }

    val badges = if (uiState.showNotificationDots) uiState.badges else emptyMap()

    // The grid's, the card's and the editor's app tap: the launcher's own icon opens Settings, any
    // other app launches. True when the tap was handled (the drawer closed behind a launch); a failed
    // launch keeps the drawer, and the surface the tap came from, open with the ViewModel's notice.
    val openApp: (AppItem) -> Boolean = { app ->
        if (app.packageName == context.packageName) {
            onOpenSettings()
            true
        } else if (viewModel.onAppClick(app, launchSources?.take(app))) {
            onClose()
            true
        } else {
            false
        }
    }

    // All app icons in the drawer (grid, folder sheet, popup) honour the themed-icons setting and the
    // user's app-label text-size multiplier.
    CompositionLocalProvider(
        LocalThemedIcons provides uiState.useThemedIcons,
        LocalIconPack provides uiState.iconPackPackage,
        LocalAppLabelScale provides uiState.appLabelTextScale,
        LocalIconScale provides uiState.iconScale,
        LocalAppLabelLines provides if (uiState.twoLineLabels) 2 else 1,
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
        onFolderClick = { folder, bounds -> folderCard = folder.id to bounds },
        onFolderLongClick = { editFolderId = it.id },
        onAppClick = { openApp(it) },
        onAppLongClick = { app, bounds -> menuTarget = app to bounds },
        onDrawerDrag = onDrawerDrag,
        onDrawerSettle = onDrawerSettle,
        dragController = dragController,
        onDragOutStart = onDragOutStart,
        onDropOnHome = { app, page, cellX, cellY -> viewModel.addToHomeAt(app, page, cellX, cellY, defaultFolderName) },
        onDropOnDock = { app -> viewModel.addToDock(app) },
        showLabels = uiState.showLabels,
        calc = uiState.calc,
        settingResults = uiState.settingResults,
        contactResults = uiState.contactResults,
        searchPending = uiState.searchPending,
        locked = uiState.desktopLocked,
        gridState = gridState,
        workApps = uiState.workApps,
        workProfile = uiState.workProfile,
        selectedTab = uiState.selectedTab,
        onSelectTab = viewModel::selectTab,
        onSetWorkPaused = viewModel::setWorkPaused,
        workGridState = workGridState,
        modifier = modifier,
    )

    val menu = menuTarget
    if (menu != null) {
        val (app, bounds) = menu
        val inHome = app.key in uiState.homeKeys
        val inDock = app.key in uiState.dockKeys
        // A full visible dock can't show another favorite — offering "Add to dock" would appear to
        // do nothing (the new favorite lands in the invisible hidden tail). Hide the add action then.
        val dockFull = uiState.dockKeys.size >= uiState.dockColumns
        // Anchor to the icon's edge (not its centre) so the popup clears it with a small gap: below
        // the cell for top-half icons, above it for bottom-half ones.
        val preferAbove = bounds.center.y > windowHeightPx / 2
        val anchorY = (if (preferAbove) bounds.top else bounds.bottom).roundToInt()
        val anchor = IntOffset(bounds.center.x.roundToInt(), anchorY)
        AppActionPopup(
            app = app,
            anchor = anchor,
            preferAbove = preferAbove,
            actions = listOfNotNull(
                PopupAction(stringResource(R.string.app_info), LauncherIcons.Info) { AppActions.openAppInfo(context, app) },
                PopupAction(stringResource(R.string.rename), LauncherIcons.Edit) { renameTarget = app },
                PopupAction(
                    stringResource(if (inHome) R.string.remove_from_home else R.string.add_to_home),
                    if (inHome) LauncherIcons.Close else LauncherIcons.Add,
                ) {
                    if (inHome) viewModel.removeFromHome(app) else viewModel.addToHome(app)
                },
                // Offer "Remove from dock" for a member, "Add to dock" only when a visible slot is
                // free; a full dock hides the add action instead of appearing to do nothing.
                if (inDock) {
                    PopupAction(stringResource(R.string.remove_from_dock), LauncherIcons.Close) {
                        viewModel.removeFromDock(app)
                    }
                } else if (!dockFull) {
                    PopupAction(stringResource(R.string.add_to_dock), LauncherIcons.Add) {
                        viewModel.addToDock(app)
                    }
                } else {
                    null
                },
                PopupAction(stringResource(R.string.hide_app), LauncherIcons.VisibilityOff) { viewModel.hideApp(app) },
                PopupAction(stringResource(R.string.uninstall), LauncherIcons.Delete) { AppActions.uninstall(context, app) },
            ),
            onDismiss = { menuTarget = null },
            onPinShortcut = { item -> viewModel.pinShortcut(item) },
            onWidgets = if (uiState.desktopLocked) null else onShowWidgets?.let { { it(app) } },
            // Opened at the lift, under the still-held finger (issue #12).
            held = dragController.isDragging,
        )
    }

    renameTarget?.let { app ->
        EditAppDialog(
            app = app,
            onSave = { name, icon -> viewModel.editApp(app, name, icon) },
            onReset = { viewModel.resetApp(app) },
            onDismiss = { renameTarget = null },
        )
    }

    // Close the folder's card/editor if its folder was deleted (or all members removed and it's gone).
    LaunchedEffect(editFolderId, folderCard, uiState.folders) {
        if (editFolderId != null && uiState.folders.none { it.id == editFolderId }) editFolderId = null
        if (folderCard != null && uiState.folders.none { it.id == folderCard?.first }) folderCard = null
    }
    val card = folderCard
    val cardFolder = card?.let { (id, _) -> uiState.folders.firstOrNull { it.id == id } }
    if (card != null && cardFolder != null) {
        DrawerFolderCard(
            folder = cardFolder,
            bounds = card.second,
            badges = badges,
            badgeShowCount = uiState.notificationDotCount,
            badgeScale = uiState.notificationDotScale,
            onAppClick = { if (openApp(it)) folderCard = null },
            onDismiss = { folderCard = null },
        )
    }
    val openFolder = editFolderId?.let { id -> uiState.folders.firstOrNull { it.id == id } }
    if (openFolder != null) {
        DrawerFolderSheet(
            folder = openFolder,
            candidateApps = uiState.folderCandidates,
            badges = badges,
            badgeShowCount = uiState.notificationDotCount,
            badgeScale = uiState.notificationDotScale,
            onRename = { viewModel.renameDrawerFolder(openFolder.id, it) },
            onSetIcon = { viewModel.setDrawerFolderIcon(openFolder.id, it) },
            onAppClick = { if (openApp(it)) editFolderId = null },
            onRemoveApp = { viewModel.removeAppFromDrawerFolder(openFolder.id, it.key) },
            onAddApps = { keys -> viewModel.addAppsToDrawerFolder(openFolder.id, keys) },
            // Sorted A-Z the order is not the user's to change, and one folder has nowhere to go.
            onMove = if (uiState.sortDrawerFolders || uiState.folders.size < 2) {
                null
            } else {
                { by -> viewModel.moveDrawerFolder(openFolder.id, by) }
            },
            canMoveEarlier = uiState.folders.indexOf(openFolder) > 0,
            canMoveLater = uiState.folders.indexOf(openFolder) < uiState.folders.lastIndex,
            onDelete = {
                viewModel.deleteDrawerFolder(openFolder.id)
                editFolderId = null
            },
            onDismiss = { editFolderId = null },
        )
    }
    }
    }
}

/** The test tag of the card a tap opens a drawer folder as. */
internal const val DRAWER_FOLDER_CARD_TAG = "drawer-folder-card"

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
    onFolderClick: (DrawerFolderUi, Rect) -> Unit,
    onFolderLongClick: (DrawerFolderUi) -> Unit,
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
    searchPending: Boolean,
    settingResults: List<SearchResult.Setting>,
    contactResults: List<SearchResult.Contact>,
    locked: Boolean,
    gridState: LazyGridState,
    workApps: List<AppItem>,
    workProfile: WorkProfileUi?,
    selectedTab: DrawerTab,
    onSelectTab: (DrawerTab) -> Unit,
    onSetWorkPaused: (Boolean) -> Unit,
    workGridState: LazyGridState,
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
                // Only the FINGER may start a pull-to-close. A fast downward fling that lands the grid
                // at its top delivers its leftover as Fling-source deltas here — feeding those in set
                // `pulling` and let onPreFling slam the drawer shut when the user only meant to scroll
                // to the top. Fling leftovers now go to the grid's stretch overscroll instead.
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
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
                                Icon(
                                    painter = painterResource(LauncherIcons.Close),
                                    contentDescription = stringResource(R.string.search_clear),
                                    tint = Accent,
                                )
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
            var gridWidthPx by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            // Icon size derived from the column width so 6–7 columns fit a narrow screen (the fixed
            // 56dp default bled into neighbouring cells there). 56dp until the grid is measured.
            // The user's icon-size setting scales the nominal 56dp before the column clamp.
            val nominalIconSize = 56.dp * LocalIconScale.current
            val drawerIconSize = if (gridWidthPx > 0 && columns > 0) {
                iconSizeForCell(with(density) { (gridWidthPx.toFloat() / columns).toDp() }, nominalIconSize)
            } else {
                nominalIconSize
            }
            // One grid shape for every page — the Personal page (the idle drawer as before), the
            // Work page and the search results — so they all measure the same icon size.
            @Composable
            fun DrawerGrid(state: LazyGridState, bottomPadding: Dp, content: LazyGridScope.() -> Unit) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = state,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + bottomPadding),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .onSizeChanged { gridWidthPx = it.width },
                    content = content,
                )
            }
            val idleContent: LazyGridScope.() -> Unit = {
                run {
                    if (frequentApps.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "frequent" }) {
                            FrequentAppsCard(
                                apps = frequentApps,
                                columns = columns,
                                badges = badges,
                                badgeShowCount = badgeShowCount,
                                badgeScale = badgeScale,
                                showLabels = showLabels,
                                iconSize = drawerIconSize,
                                onAppClick = onAppClick,
                                onAppLongClick = onAppLongClick,
                            )
                        }
                    }
                    items(items = folders, key = { "folder-${it.id}" }, contentType = { "folder" }) { folder ->
                        DrawerFolderTile(
                            folder = folder,
                            showLabel = showLabels,
                            tileSize = drawerIconSize,
                            // Aggregated like the home-screen FolderIcon: members left the flat A–Z
                            // grid, so without this their unread badges vanished from the drawer.
                            badgeCount = folder.apps.sumOf { badges[it.badgeKey] ?: 0 },
                            badgeShowCount = badgeShowCount,
                            badgeScale = badgeScale,
                            onClick = { bounds -> onFolderClick(folder, bounds) },
                            onLongClick = { onFolderLongClick(folder) },
                        )
                    }
                    appCells(apps, badges, badgeShowCount, badgeScale, showLabels, drawerIconSize, onAppClick,
                        onAppLongClick, dragController, onDragOutStart, onDropOnHome, onDropOnDock, haptics, locked)
                }
            }
            val searchContent: LazyGridScope.() -> Unit = {
                run {
                    calc?.let { c ->
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "calc" }) {
                            val copiedMsg = stringResource(R.string.search_calc_copied)
                            CalcResultCard(c) {
                                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                                clip?.setPrimaryClip(android.content.ClipData.newPlainText("result", c.result))
                                android.widget.Toast.makeText(context, copiedMsg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (apps.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "header" }) {
                            ExpressiveSectionTitle(stringResource(R.string.search_section_apps))
                        }
                        appCells(apps, badges, badgeShowCount, badgeScale, showLabels, drawerIconSize, onAppClick,
                            onAppLongClick, dragController, onDragOutStart, onDropOnHome, onDropOnDock, haptics, locked)
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
                    // No app, setting, contact or calculator hit: say so — a silently empty grid
                    // under the search box was indistinguishable from a stuck drawer. Not while the
                    // results are still the previous query's: that flashed "No results" on the first
                    // letter of every search.
                    if (!searchPending && calc == null && apps.isEmpty() && settingResults.isEmpty() && contactResults.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = { "empty" }) {
                            Text(
                                text = stringResource(R.string.search_no_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                            )
                        }
                    }
                }
            }
            // The stored tab goes back to Personal whenever the Work tab goes (profile removed, last
            // work app hidden): the pager must not reopen on Work the next time the tab reappears.
            val hasWorkTab = workProfile != null
            LaunchedEffect(hasWorkTab) { if (!hasWorkTab) onSelectTab(DrawerTab.Personal) }
            when {
                searching -> DrawerGrid(gridState, 0.dp, searchContent)
                // No Work tab: the drawer is exactly the 0.9.1 drawer.
                workProfile == null -> DrawerGrid(gridState, 0.dp, idleContent)
                else -> {
                    // The pager owns the tab while it is on screen (PagerTabRow); the ViewModel
                    // stores the page it rests on, which is where a pager rebuilt after a search
                    // starts, and what the Work tab going (profile removed, last work app hidden)
                    // resets to Personal.
                    val pagerState = rememberPagerState(initialPage = selectedTab.ordinal) { DrawerTab.entries.size }
                    PagerTabRow(pagerState, onSelectTab)
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        // A lifted icon must never start a page swipe under the finger.
                        userScrollEnabled = !dragController.isDragging,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (DrawerTab.entries[page]) {
                            DrawerTab.Personal -> DrawerGrid(gridState, 0.dp, idleContent)
                            DrawerTab.Work -> WorkPage(
                                workProfile = workProfile,
                                onSetWorkPaused = onSetWorkPaused,
                                // The page reserves the floating button's measured height under the last row.
                                grid = { bottomPadding ->
                                    DrawerGrid(workGridState, bottomPadding) {
                                        appCells(workApps, badges, badgeShowCount, badgeScale, showLabels, drawerIconSize,
                                            onAppClick, onAppLongClick, dragController, onDragOutStart, onDropOnHome,
                                            onDropOnDock, haptics, locked)
                                    }
                                },
                            )
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

/** A drawer folder tile: a rounded 2×2 preview of its first apps + label. A tap opens the folder's
 *  card ([onClick] gets the tile's root bounds to anchor it by); a long-press (with the haptic of an
 *  app's) opens its editor. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerFolderTile(
    folder: DrawerFolderUi,
    showLabel: Boolean,
    onClick: (Rect) -> Unit,
    onLongClick: () -> Unit,
    tileSize: Dp = 56.dp,
    badgeCount: Int = 0,
    badgeShowCount: Boolean = true,
    badgeScale: Float = 1f,
) {
    var bounds by remember(folder.id) { mutableStateOf(Rect.Zero) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            // combinedClickable ticks the long-press haptic itself (as an app's does). The label is
            // what TalkBack reads for the hold: the editor has no other way in.
            .combinedClickable(
                onClick = { onClick(bounds) },
                onLongClickLabel = stringResource(R.string.folder_edit),
                onLongClick = onLongClick,
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // EVERYTHING inside scales with the tile — padding and gaps included. A fixed padding/gap
        // with linearly scaled icons inverted the margin below ~45dp tiles (6–7 drawer columns on a
        // narrow screen), squeezing the 2×2 preview asymmetrically out of its card.
        Box(contentAlignment = Alignment.TopEnd) {
            DrawerFolderGlyph(folder, folder.icon, tileSize)
            NotificationBadge(count = badgeCount, showCount = badgeShowCount, scale = badgeScale)
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = folder.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = LocalAppLabelLines.current,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A drawer folder's picture: the [icon] picked for it, else a rounded 2×2 preview of its first apps. */
@Composable
private fun DrawerFolderGlyph(folder: DrawerFolderUi, icon: CustomIcon?, tileSize: Dp) {
    if (icon != null) {
        // Its pack uninstalled or its picture gone: the preview again, never a blank tile.
        PickedIcon(icon, tileSize, contentDescription = folder.name, fallback = { DrawerFolderPreview(folder, tileSize) })
    } else {
        DrawerFolderPreview(folder, tileSize)
    }
}

/** A drawer folder's 2×2 preview of its first four apps. EVERYTHING inside scales with the tile —
 *  padding and gaps included: a fixed padding/gap with linearly scaled icons inverted the margin
 *  below ~45dp tiles (6–7 drawer columns on a narrow screen), squeezing the preview asymmetrically
 *  out of its card. */
@Composable
private fun DrawerFolderPreview(folder: DrawerFolderUi, tileSize: Dp) {
    val scale = tileSize / 56.dp
    Box(
        modifier = Modifier
            .size(tileSize)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(7.dp * scale),
        contentAlignment = Alignment.Center,
    ) {
        val mini = 18.dp * scale
        val gap = 2.dp * scale
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                FolderSlot(folder.apps.getOrNull(0), mini); FolderSlot(folder.apps.getOrNull(1), mini)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                FolderSlot(folder.apps.getOrNull(2), mini); FolderSlot(folder.apps.getOrNull(3), mini)
            }
        }
    }
}

@Composable
private fun FolderSlot(app: AppItem?, size: Dp) {
    if (app == null) {
        Spacer(Modifier.size(size))
    } else {
        AppIcon(appItem = app, labelColor = Color.Transparent, showLabel = false, iconSize = size)
    }
}

private const val FOLDER_CARD_COLUMNS = 4
private val FOLDER_CARD_WIDTH = 328.dp

/**
 * A tapped folder: its apps in a card by its tile, as the home screen opens a folder — the thumb
 * stays where it was instead of travelling to a sheet at the bottom. A tap launches the app; nothing
 * on the card changes the folder, that is the editor's (a long-press away), so ordinary use cannot
 * rename, empty or delete a folder by accident.
 */
@Composable
private fun DrawerFolderCard(
    folder: DrawerFolderUi,
    bounds: Rect,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    onAppClick: (AppItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    // Anchored like the app menu: below a tile in the upper half of the window, above one lower down.
    val preferAbove = bounds.center.y > windowSize.height / 2
    val anchor = IntOffset(bounds.center.x.roundToInt(), (if (preferAbove) bounds.top else bounds.bottom).roundToInt())
    val width = with(LocalDensity.current) { minOf(FOLDER_CARD_WIDTH, windowSize.width.toDp() - 16.dp) }
    // The grid's rule: the icon shrinks to its cell when the icon-size setting would overflow it.
    val iconSize = iconSizeForCell((width - 16.dp) / FOLDER_CARD_COLUMNS, 56.dp * LocalIconScale.current)
    val palette = LocalExpressivePalette.current
    AnchoredPopupCard(anchor = anchor, preferAbove = preferAbove, onDismiss = onDismiss, width = width) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag(DRAWER_FOLDER_CARD_TAG),
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (folder.apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.folder_card_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.dim,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            folder.apps.chunked(FOLDER_CARD_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { app ->
                        AppIcon(
                            appItem = app,
                            labelColor = palette.text,
                            iconSize = iconSize,
                            showLabel = true,
                            badgeCount = badges[app.badgeKey] ?: 0,
                            badgeShowCount = badgeShowCount,
                            badgeScale = badgeScale,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onAppClick(app) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                    repeat(FOLDER_CARD_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The folder editor sheet: rename, member apps (tap launches, long-press removes), add apps, delete
 *  (asked first) and, when the order is the user's ([onMove] given), a step earlier or later among
 *  the folders. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DrawerFolderSheet(
    folder: DrawerFolderUi,
    candidateApps: List<AppItem>,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    onRename: (String) -> Unit,
    onSetIcon: (CustomIcon?) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onRemoveApp: (AppItem) -> Unit,
    onAddApps: (List<String>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onMove: ((by: Int) -> Unit)? = null,
    canMoveEarlier: Boolean = false,
    canMoveLater: Boolean = false,
) {
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The sheet is its own window: re-apply the long-press delay setting for the members' long-press.
    ModalBottomSheet(onDismissRequest = onDismiss) { LongPressDelay {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            var name by remember(folder.id) { mutableStateOf(folder.name) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The folder's icon: a tap picks another (Nova and Lawnchair let folders have one).
                FolderIconButton(
                    icon = folder.icon,
                    preview = { icon, size -> DrawerFolderGlyph(folder, icon, size) },
                    onPick = onSetIcon,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; onRename(it) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name_label)) },
                    modifier = Modifier.weight(1f),
                )
            }
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
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.folder_delete)) }
            }
            // A row of its own: beside the two text buttons the chevrons overflowed the sheet in
            // Finnish and French (and in English at a large font scale).
            if (onMove != null) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.folder_move_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onMove(-1) }, enabled = canMoveEarlier) {
                        Icon(painterResource(LauncherIcons.ChevronLeft), contentDescription = stringResource(R.string.folder_move_earlier))
                    }
                    IconButton(onClick = { onMove(1) }, enabled = canMoveLater) {
                        Icon(painterResource(LauncherIcons.ChevronRight), contentDescription = stringResource(R.string.folder_move_later))
                    }
                }
            }
        }
    } }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.folder_delete_title)) },
            text = { Text(stringResource(R.string.folder_delete_text, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.folder_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.folder_cancel)) }
            },
        )
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
    iconSize: Dp,
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
                        iconSize = iconSize,
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
internal fun LazyGridScope.appCells(
    apps: List<AppItem>,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showLabels: Boolean,
    iconSize: Dp,
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
        // The gesture below lives as long as the key (a label or icon change does not restart it)
        // and must act on the app as it is NOW: with the first touch's item, Edit opened a second
        // time showed the old icon and saving a name wiped a picture picked a moment earlier.
        val currentApp by rememberUpdatedState(app)
        // [bounds] anchors the popup and is clipped by the grid; [origin] is the cell's real top-left,
        // which a cell half scrolled under the grid's top edge still needs for the finger's position.
        var bounds by remember { mutableStateOf(Rect.Zero) }
        var origin by remember { mutableStateOf(Offset.Zero) }
        AppIcon(
            appItem = app,
            labelColor = MaterialTheme.colorScheme.onSurface,
            showLabel = showLabels,
            iconSize = iconSize,
            badgeCount = badges[app.badgeKey] ?: 0,
            badgeShowCount = badgeShowCount,
            badgeScale = badgeScale,
            modifier = Modifier
                .onGloballyPositioned { bounds = it.boundsInRoot(); origin = it.positionInRoot() }
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
                        // The lift starts where the finger is when the hold completes.
                        var holdPos = down.position
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
                                // Don't consume: let the LazyGrid scroll a quick drag. Vertical travel
                                // past the slop is that scroll; a sideways roll ends the hold once
                                // the finger leaves the icon.
                                if (c.isConsumed ||
                                    abs(c.position.y - down.position.y) > slop ||
                                    !IconPress.staysOnIcon(c.position, size, slop)
                                ) {
                                    outcome = 2
                                    return@withTimeoutOrNull
                                }
                                holdPos = c.position
                                // The Personal / Work pager claims a sideways swipe in the Main pass
                                // AFTER this child, and the page then follows the finger, so the
                                // pointer never leaves the icon: only the Final pass shows the claim
                                // (the same rule as awaitIconPress in core/ui). Without it a flick
                                // between the tabs launched the app under the finger.
                                awaitPointerEvent(PointerEventPass.Final)
                                if (c.isConsumed) {
                                    outcome = 2
                                    return@withTimeoutOrNull
                                }
                            }
                        }
                        when (outcome) {
                            1 -> {
                                onAppClick(currentApp)
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
                        val token = Any()
                        if (!dragController.start(currentApp, DragSource.Drawer, origin + holdPos, token)) {
                            return@awaitEachGesture
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // The menu opens now, by the icon, under the still-held finger (issue #12);
                        // pulling the icon on closes it (DismissWhenDragMoves) and the drag goes on.
                        onAppLongClick(currentApp, bounds)
                        val dragStartPx = IconPress.DragStartThreshold.toPx()
                        try {
                            val completed = drag(down.id) { change ->
                                change.consume()
                                if (!dragController.owns(token)) return@drag
                                // Only commit to a drag-out once the finger is pulled past the
                                // drag-start threshold — otherwise a still long-press (with finger
                                // jitter) would collapse the drawer instead of just showing the menu.
                                if (!dragController.moving &&
                                    IconPress.startsDrag(change.position, holdPos, dragStartPx)
                                ) {
                                    dragController.beginMove()
                                    onDragOutStart()
                                }
                                if (dragController.moving) {
                                    dragController.update(origin + change.position)
                                }
                            }
                            // Nothing on release once HOME cancelled the lift.
                            val live = completed && dragController.owns(token)
                            if (live && dragController.moving) {
                                val root = dragController.rootPosition
                                when {
                                    dragController.isOverDock(root) && dragController.dockHasSpace ->
                                        onDropOnDock(currentApp)
                                    dragController.isOverGrid(root) -> {
                                        val (page, cx, cy) = dragController.cellAt(root)
                                        onDropOnHome(currentApp, page, cx, cy)
                                    }
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            // A still release leaves the menu, opened at the lift, as it is.
                        } finally {
                            // Reset even on cancellation (node disposed / pointerInput restarted),
                            // mirroring Workspace's local-drag path, so a dead gesture can't leave
                            // the shared controller lifted.
                            dragController.stop(token)
                        }
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
