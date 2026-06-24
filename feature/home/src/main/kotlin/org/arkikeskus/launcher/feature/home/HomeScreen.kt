package org.arkikeskus.launcher.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.AppShortcuts
import org.arkikeskus.launcher.ui.component.AppIcon
import kotlin.math.roundToInt

/**
 * Home screen: a paged [Workspace] of app shortcuts + the dock. All home gestures (icon drag, swipe
 * up/down, long-press settings, page swipe) live inside [Workspace] so they don't fight each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    homeSignals: Flow<Unit> = emptyFlow(),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val context = LocalContext.current
    val density = LocalDensity.current
    // The single Pixel-style long-press menu, anchored to the long-pressed icon.
    var menuTarget by remember { mutableStateOf<AppMenuTarget?>(null) }
    var openFolderId by remember { mutableStateOf<Long?>(null) }
    val defaultFolderName = stringResource(R.string.folder_default_name)

    // Shared drag state spanning the workspace and the dock (Launcher3-style drag layer/controller).
    val dragController = rememberHomeDragController()
    SideEffect {
        dragController.dockItemCount = uiState.dockApps.size
        dragController.dockHasSpace = settings.dockEnabled && uiState.dockApps.size < settings.dockColumns
    }
    // A drag that starts moving dismisses the menu (so the menu and drop bar never coexist).
    LaunchedEffect(dragController.moving) {
        if (dragController.moving) menuTarget = null
    }
    // The floating icon is positioned in root coords; subtract this Box's origin to place it locally.
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }

    // Notification badges (empty when disabled), and whether to render the count or a plain dot.
    val badges = if (settings.showNotificationDots) uiState.badges else emptyMap()
    val badgeShowCount = settings.notificationDotCount
    val badgeScale = settings.notificationDotScale

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { screenOrigin = it.positionInRoot() },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Workspace(
                pageCount = uiState.pageCount,
                columns = settings.homeColumns,
                rows = viewModel.rows,
                entries = uiState.entries,
                badges = badges,
                badgeShowCount = badgeShowCount,
                badgeScale = badgeScale,
                showLabels = settings.showHomeLabels,
                showPageIndicator = settings.showPageIndicator,
                swipeUpForDrawer = settings.swipeUpForDrawer,
                swipeDownForNotifications = settings.swipeDownForNotifications,
                homeSignals = homeSignals,
                dragController = dragController,
                onAppClick = viewModel::launch,
                onAppMenu = { app, anchor -> menuTarget = AppMenuTarget(app, anchor, DragSource.Home) },
                onMove = viewModel::moveItem,
                onMoveToDock = { app, index -> viewModel.moveToDock(app, index) },
                onOpenFolder = { openFolderId = it.id },
                onCreateFolder = { target, dropped -> viewModel.createFolder(target, dropped, defaultFolderName) },
                onAddToFolder = { app, folderId -> viewModel.addToFolder(app, folderId) },
                onOpenDrawer = onOpenDrawer,
                onOpenNotifications = { NotificationShade.expand(context) },
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )

            if (settings.dockEnabled && uiState.dockApps.isNotEmpty()) {
                Dock(
                    apps = uiState.dockApps,
                    badges = badges,
                    badgeShowCount = badgeShowCount,
                    badgeScale = badgeScale,
                    showLabels = settings.showDockLabels,
                    backgroundAlpha = settings.dockBackgroundOpacity,
                    dragController = dragController,
                    onAppClick = viewModel::launch,
                    onReorder = viewModel::reorderDock,
                    onMoveToHome = { app, page, cellX, cellY -> viewModel.moveToHome(app, page, cellX, cellY) },
                    onAppMenu = { app, anchor -> menuTarget = AppMenuTarget(app, anchor, DragSource.Dock) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }

        // Single floating icon for any in-progress drag, drawn above both surfaces so it can travel
        // between them. Positioned by the finger's root coords (minus this Box's origin).
        val dragged = dragController.draggedApp
        if (dragged != null && dragController.moving) {
            val sizeDp = 56.dp
            val halfPx = with(density) { (sizeDp / 2).toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragController.rootPosition.x - screenOrigin.x - halfPx).roundToInt(),
                            (dragController.rootPosition.y - screenOrigin.y - halfPx).roundToInt(),
                        )
                    }
                    .size(sizeDp)
                    .graphicsLayer {
                        alpha = 0.92f
                        scaleX = 1.1f
                        scaleY = 1.1f
                    },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    appItem = dragged,
                    labelColor = Color.White,
                    showLabel = false,
                    iconSize = 52.dp,
                )
            }
        }
    }

    val menu = menuTarget
    if (menu != null) {
        AppActionPopup(
            target = menu,
            onAppInfo = { AppActions.openAppInfo(context, menu.app) },
            onUninstall = { AppActions.uninstall(context, menu.app) },
            onRemove = {
                if (menu.source == DragSource.Dock) viewModel.removeFromDock(menu.app)
                else viewModel.removeFromHome(menu.app)
            },
            onDismiss = { menuTarget = null },
        )
    }

    val openFolder = openFolderId?.let { id ->
        uiState.entries.filterIsInstance<PlacedFolder>().firstOrNull { it.id == id }
    }
    // Close the sheet if the folder dissolved (dropped to one app) while it was open.
    LaunchedEffect(openFolderId, openFolder == null) {
        if (openFolderId != null && openFolder == null) openFolderId = null
    }
    if (openFolder != null) {
        FolderSheet(
            folder = openFolder,
            badges = badges,
            badgeShowCount = badgeShowCount,
            badgeScale = badgeScale,
            onRename = { viewModel.renameFolder(openFolder.id, it) },
            onAppClick = { viewModel.launch(it) },
            onRemoveFromFolder = { viewModel.removeFromFolder(it, openFolder.id) },
            onDismiss = { openFolderId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FolderSheet(
    folder: PlacedFolder,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    onRename: (String) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onRemoveFromFolder: (AppItem) -> Unit,
    onDismiss: () -> Unit,
) {
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
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.folder_long_press_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
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
                                onClick = {
                                    onAppClick(app)
                                    onDismiss()
                                },
                                onLongClick = { onRemoveFromFolder(app) },
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** The long-pressed app and where its menu should anchor (which surface it came from). */
private data class AppMenuTarget(val app: AppItem, val anchor: IntOffset, val source: DragSource)

/** The single Pixel-style long-press menu, anchored to the icon (above it for dock items). */
@Composable
private fun AppActionPopup(
    target: AppMenuTarget,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fromDock = target.source == DragSource.Dock
    val positionProvider = remember(target.anchor, fromDock) {
        AnchoredPopupPositionProvider(target.anchor, preferAbove = fromDock)
    }
    val context = LocalContext.current
    // App-specific shortcuts (Pixel-style "New tab" etc.), queried off the main thread.
    var shortcuts by remember(target.app.key) { mutableStateOf<List<AppShortcuts.Item>>(emptyList()) }
    LaunchedEffect(target.app.key) {
        shortcuts = withContext(Dispatchers.IO) { AppShortcuts.query(context, target.app) }
    }
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val density = LocalDensity.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width.toFloat()
    val cardWidth = 280.dp
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // Compute the caret's x within the (fixed-width) card so it points at the icon even when the
        // card is clamped to the screen edge — using the same clamp as the position provider.
        val cardWidthPx = with(density) { cardWidth.toPx() }
        val caretWidth = 22.dp
        val caretWidthPx = with(density) { caretWidth.toPx() }
        val cardLeft = (target.anchor.x - cardWidthPx / 2f).coerceIn(
            POPUP_MARGIN_PX.toFloat(),
            (windowWidthPx - cardWidthPx - POPUP_MARGIN_PX).coerceAtLeast(POPUP_MARGIN_PX.toFloat()),
        )
        val caretStart = with(density) {
            ((target.anchor.x - cardLeft) - caretWidthPx / 2f)
                .coerceIn(14f, cardWidthPx - caretWidthPx - 14f)
                .toDp()
        }
        // Card + a small caret pointing at the icon (above the card for home, below it for dock).
        Column(modifier = Modifier.width(cardWidth)) {
            if (!fromDock) {
                Caret(pointingUp = true, color = cardColor, modifier = Modifier.padding(start = caretStart))
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardColor,
                tonalElevation = 3.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    shortcuts.forEach { shortcut ->
                        PopupRow(shortcut.label) {
                            AppShortcuts.start(context, shortcut)
                            onDismiss()
                        }
                    }
                    if (shortcuts.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    PopupRow(stringResource(R.string.app_info)) { onAppInfo(); onDismiss() }
                    PopupRow(
                        stringResource(if (fromDock) R.string.dock_remove else R.string.home_remove),
                    ) { onRemove(); onDismiss() }
                    PopupRow(stringResource(R.string.uninstall)) { onUninstall(); onDismiss() }
                }
            }
            if (fromDock) {
                Caret(pointingUp = false, color = cardColor, modifier = Modifier.padding(start = caretStart))
            }
        }
    }
}

/** Small triangle that visually ties the popup to the icon it acts on. */
@Composable
private fun Caret(pointingUp: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 22.dp, height = 10.dp)) {
        val path = Path().apply {
            if (pointingUp) {
                moveTo(0f, size.height)
                lineTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height)
                lineTo(size.width, 0f)
            }
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PopupRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private const val POPUP_MARGIN_PX = 24

/** Places the menu centered under (or above, for dock) the icon, clamped to the screen. */
private class AnchoredPopupPositionProvider(
    private val anchor: IntOffset,
    private val preferAbove: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = POPUP_MARGIN_PX
        val x = (anchor.x - popupContentSize.width / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = if (preferAbove) {
            (anchor.y - popupContentSize.height - margin).coerceAtLeast(margin)
        } else {
            (anchor.y + margin)
                .coerceAtMost((windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        }
        return IntOffset(x, y)
    }
}
