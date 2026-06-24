package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppActionPopup
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.PopupAction
import org.arkikeskus.launcher.ui.component.AppIcon
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
    viewModel: AppDrawerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuTarget by remember { mutableStateOf<Pair<AppItem, IntOffset>?>(null) }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height

    val badges = if (uiState.showNotificationDots) uiState.badges else emptyMap()

    AppDrawerContent(
        apps = uiState.apps,
        query = uiState.query,
        columns = uiState.columns,
        badges = badges,
        badgeShowCount = uiState.notificationDotCount,
        badgeScale = uiState.notificationDotScale,
        showSearch = uiState.showSearch,
        onQueryChange = viewModel::onQueryChange,
        onAppClick = { app ->
            if (app.packageName == context.packageName) {
                onOpenSettings()
            } else if (viewModel.onAppClick(app)) {
                // Keep the drawer open if the launch failed, so the user isn't dumped back to home
                // with no explanation.
                onClose()
            }
        },
        onAppLongClick = { app, center ->
            menuTarget = app to IntOffset(center.x.roundToInt(), center.y.roundToInt())
        },
        onDrawerDrag = onDrawerDrag,
        onDrawerSettle = onDrawerSettle,
        dragController = dragController,
        onDragOutStart = onDragOutStart,
        onDropOnHome = { app, page, cellX, cellY -> viewModel.addToHomeAt(app, page, cellX, cellY) },
        onDropOnDock = { app -> viewModel.addToDock(app) },
        showLabels = uiState.showLabels,
        modifier = modifier,
    )

    val menu = menuTarget
    if (menu != null) {
        val (app, anchor) = menu
        val inHome = app.key in uiState.homeKeys
        val inDock = app.key in uiState.dockKeys
        AppActionPopup(
            app = app,
            anchor = anchor,
            preferAbove = anchor.y > windowHeightPx / 2,
            actions = listOf(
                PopupAction(stringResource(R.string.app_info)) { AppActions.openAppInfo(context, app) },
                PopupAction(
                    stringResource(if (inHome) R.string.remove_from_home else R.string.add_to_home),
                ) {
                    if (inHome) viewModel.removeFromHome(app) else viewModel.addToHome(app)
                },
                PopupAction(
                    stringResource(if (inDock) R.string.remove_from_dock else R.string.add_to_dock),
                ) {
                    if (inDock) viewModel.removeFromDock(app) else viewModel.addToDock(app)
                },
                PopupAction(stringResource(R.string.hide_app)) { viewModel.hideApp(app) },
                PopupAction(stringResource(R.string.uninstall)) { AppActions.uninstall(context, app) },
            ),
            onDismiss = { menuTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppDrawerContent(
    apps: List<AppItem>,
    query: String,
    columns: Int,
    badges: Map<String, Int>,
    badgeShowCount: Boolean,
    badgeScale: Float,
    showSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem, Offset) -> Unit,
    onDrawerDrag: (Float) -> Unit,
    onDrawerSettle: (Float) -> Unit,
    dragController: HomeDragController,
    onDragOutStart: () -> Unit,
    onDropOnHome: (AppItem, Int, Int, Int) -> Unit,
    onDropOnDock: (AppItem) -> Unit,
    showLabels: Boolean,
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
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.app_drawer_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                items(items = apps, key = { it.key }, contentType = { "app" }) { app ->
                    var center by remember { mutableStateOf(Offset.Zero) }
                    var itemRoot by remember { mutableStateOf(Offset.Zero) }
                    AppIcon(
                        appItem = app,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        showLabel = showLabels,
                        maxLabelLines = 2,
                        badgeCount = badges[app.badgeKey] ?: 0,
                        badgeShowCount = badgeShowCount,
                        badgeScale = badgeScale,
                        modifier = Modifier
                            .onGloballyPositioned {
                                val b = it.boundsInRoot()
                                center = b.center
                                itemRoot = b.topLeft
                            }
                            // One unified gesture (like Workspace/Dock) but non-consuming until the
                            // long-press fires, so a quick drag still scrolls the grid: quick tap
                            // launches; a still long-press lifts → drag out to home/dock, or with no
                            // movement opens the long-press menu.
                            .pointerInput(app.key) {
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
                                    // LONG PRESS → lift into the shared controller (drawer source).
                                    // The finger's root position is [itemRoot] (captured while the
                                    // drawer is open) plus the pointer's local position. The drawer is
                                    // hidden with alpha (not translated) during the drag, so its local
                                    // coordinate space stays put and this stays accurate.
                                    dragController.start(app, DragSource.Drawer, itemRoot + down.position)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val completed = drag(down.id) { change ->
                                        change.consume()
                                        if (!dragController.moving) {
                                            dragController.beginMove()
                                            onDragOutStart()
                                        }
                                        dragController.update(itemRoot + change.position)
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
                                        // No movement → static long-press → show the menu.
                                        onAppLongClick(app, center)
                                    }
                                    dragController.stop()
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
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
