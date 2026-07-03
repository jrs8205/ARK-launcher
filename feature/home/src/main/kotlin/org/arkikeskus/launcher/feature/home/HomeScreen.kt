package org.arkikeskus.launcher.feature.home

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppActionPopup
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.AppShortcuts
import org.arkikeskus.launcher.ui.DefaultLauncher
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.IconMenuItem
import org.arkikeskus.launcher.ui.IconMenuPopup
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.PopupAction
import org.arkikeskus.launcher.ui.RenameDialog
import org.arkikeskus.launcher.ui.rememberHomeDragController
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.arkikeskus.launcher.ui.component.LocalIconPack
import org.arkikeskus.launcher.ui.component.LocalThemedIcons
import kotlin.math.abs
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
    onDrawerDrag: (Float) -> Unit = {},
    onDrawerSettle: (Float) -> Unit = {},
    dragController: HomeDragController = rememberHomeDragController(),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    // True briefly after a heads-up notification, while the system transiently shows its own status bar
    // over ours; used to blank the themed bar so they don't overlap.
    val headsUpActive by viewModel.headsUpActive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height

    // Optionally hide the system status bar while the launcher is foreground (immersive home). No root
    // needed; the bar returns for any other app. Re-applied on every ON_RESUME so returning from another
    // app re-hides it. A swipe from the top still transiently reveals it (Android safety behaviour).
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(settings.hideSystemStatusBar, lifecycleOwner, view) {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        fun apply() {
            val controller = window?.insetsController ?: return
            if (settings.hideSystemStatusBar) {
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(android.view.WindowInsets.Type.statusBars())
            } else {
                controller.show(android.view.WindowInsets.Type.statusBars())
            }
        }
        apply()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apply()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // First-run onboarding: when we are NOT the default home, surface the system "set default launcher"
    // dialog (RoleManager) so a fresh install — e.g. downloaded from GitHub — can become the launcher like
    // Lawnchair/Launcher3, without adb or hunting through settings. Re-checked on resume.
    var isDefaultLauncher by remember { mutableStateOf(DefaultLauncher.isDefault(context)) }
    val setDefaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isDefaultLauncher = DefaultLauncher.isDefault(context) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) isDefaultLauncher = DefaultLauncher.isDefault(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // The single Pixel-style long-press menu, anchored to the long-pressed icon.
    var menuTarget by remember { mutableStateOf<AppMenuTarget?>(null) }
    var renameTarget by remember { mutableStateOf<AppItem?>(null) }
    var openFolderId by remember { mutableStateOf<Long?>(null) }
    // Empty-area long-press options popup (anchor + whether it flips above the press point).
    var homeOptions by remember { mutableStateOf<Pair<IntOffset, Boolean>?>(null) }
    val defaultFolderName = stringResource(R.string.folder_default_name)

    val widgetHost = LocalAppWidgetHost.current
    val widgetConfigLauncher = LocalWidgetConfigLauncher.current
    val appWidgetManager = remember { AppWidgetManager.getInstance(context) }
    var showWidgetPicker by remember { mutableStateOf(false) }
    // Pending widget across the bind/configure result steps. [restoreRowId] non-null → this is a
    // RESTORE (re-bind an existing placeholder row) rather than adding a brand-new widget.
    // Saveable: while the system bind dialog or the widget's own config activity is in front, the
    // launcher is a killable background process — a plain remember lost the bind on process death
    // and the user's widget silently never appeared.
    var pendingWidget by rememberSaveable(stateSaver = PendingWidgetBindSaver) {
        mutableStateOf<PendingWidgetBind?>(null)
    }

    // Persistent, page-independent host views: created once per placed widget id and kept alive so the
    // AppWidgetHost listener is never lost when a pager page scrolls off-screen. A lost listener drops a
    // collection widget's deferred row update (notifyAppWidgetViewDataChanged) — the empty-WhatsApp bug.
    // Mirrors Launcher3's LauncherWidgetHolder keeping every host view registered for the app's lifetime.
    val widgetViews = remember { mutableStateMapOf<Int, android.appwidget.AppWidgetHostView>() }
    val widgetScrollableById = remember { mutableStateMapOf<Int, Boolean>() }
    val placedWidgetIds = remember(uiState.entries) {
        uiState.entries.filterIsInstance<PlacedWidget>().map { it.appWidgetId }.toSet()
    }
    LaunchedEffect(placedWidgetIds, widgetHost) {
        val host = widgetHost ?: return@LaunchedEffect
        placedWidgetIds.forEach { id ->
            if (widgetViews[id] == null) {
                val info = appWidgetManager.getAppWidgetInfo(id)
                if (info != null) {
                    runCatching {
                        // Mark the widget as living on the HOME screen up front — some providers
                        // (WhatsApp's chat list) won't populate until the host category is set.
                        appWidgetManager.updateAppWidgetOptions(
                            id,
                            android.os.Bundle().apply {
                                putInt(
                                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                                )
                            },
                        )
                        val v = host.createView(context, id, info)
                        v.setPadding(0, 0, 0, 0)
                        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            widgetScrollableById[id] = v.containsScrollableCollection()
                        }
                        widgetScrollableById[id] = v.containsScrollableCollection()
                        widgetViews[id] = v
                    }
                }
            }
        }
        (widgetViews.keys - placedWidgetIds).forEach {
            widgetViews.remove(it)
            widgetScrollableById.remove(it)
        }
    }

    // One-shot at startup: free any AppWidgetHost ids with no home_items row — e.g. the old bound ids
    // left over after a full backup restore replaced the layout — so the host doesn't leak widget ids.
    // The host ids are snapshotted BEFORE the (suspending) DB query: an id allocated while the query
    // runs is absent from the snapshot and can never be swept; an in-flight bind's id is excluded too.
    LaunchedEffect(widgetHost) {
        val host = widgetHost ?: return@LaunchedEffect
        val hostIds = runCatching { host.appWidgetIds }.getOrNull() ?: return@LaunchedEffect
        val dbIds = viewModel.boundWidgetIds()
        hostIds.forEach { id ->
            if (id !in dbIds && id != pendingWidget?.appWidgetId) runCatching { host.deleteAppWidgetId(id) }
        }
    }

    fun finishWidget(bind: PendingWidgetBind) {
        if (bind.restoreRowId != null) {
            // Restore: the placeholder row already has the provider + spans from the backup; just bind it.
            viewModel.bindRestoredWidget(bind.restoreRowId, bind.appWidgetId)
        } else {
            val (sx, sy) = defaultWidgetSpans(bind.provider, context)
            viewModel.addWidget(bind.appWidgetId, bind.provider.provider.flattenToString(), sx, sy)
        }
        pendingWidget = null
    }

    val reconfigureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* the widget reconfigures itself via RemoteViews; no DB change needed */ }

    fun configureOrFinish(bind: PendingWidgetBind) {
        // Launch the configuration activity through the SYSTEM (Activity-routed), not a raw
        // ACTION_APPWIDGET_CONFIGURE Intent, so the framework records the widget as configured.
        // Otherwise some providers (WhatsApp's auth-gated chat widget) stay "configuration pending" and
        // never populate. If there's no config activity, finish immediately.
        val launcher = widgetConfigLauncher
        if (bind.provider.configure != null && launcher != null) {
            pendingWidget = bind
            launcher(bind.appWidgetId) { ok ->
                val p = pendingWidget
                if (p != null) {
                    if (ok) finishWidget(p)
                    else { widgetHost?.deleteAppWidgetId(p.appWidgetId); pendingWidget = null }
                }
            }
        } else {
            finishWidget(bind)
        }
    }

    val bindLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val p = pendingWidget
        if (p != null) {
            if (result.resultCode == android.app.Activity.RESULT_OK) configureOrFinish(p)
            else { widgetHost?.deleteAppWidgetId(p.appWidgetId); pendingWidget = null }
        }
    }

    // Allocate an id and bind [provider] (silently if allowed, else via the system bind dialog), then
    // configure. [restoreRowId] != null re-binds an existing placeholder row instead of adding a new one.
    fun startBind(provider: AppWidgetProviderInfo, restoreRowId: Long?) {
        val host = widgetHost ?: return
        val id = host.allocateAppWidgetId()
        val bind = PendingWidgetBind(id, provider, restoreRowId)
        val bound = runCatching { appWidgetManager.bindAppWidgetIdIfAllowed(id, provider.provider) }.getOrDefault(false)
        if (bound) {
            configureOrFinish(bind)
        } else {
            pendingWidget = bind
            val intent = android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            }
            runCatching { bindLauncher.launch(intent) }
                .onFailure { host.deleteAppWidgetId(id); pendingWidget = null }
        }
    }

    fun startAddWidget(provider: AppWidgetProviderInfo) = startBind(provider, restoreRowId = null)

    // Re-bind a restored placeholder: resolve its provider info, then run the same bind/configure flow
    // targeting its existing row. If the provider's app is gone (uninstalled after restore), no-op.
    fun startRestoreWidget(pending: PendingWidget) {
        val provider = appWidgetManager.installedProviders.firstOrNull { it.provider == pending.provider } ?: return
        startBind(provider, restoreRowId = pending.rowId)
    }

    // A config result that arrived after process death: [pendingWidget] survived (saveable) but the
    // result callback died with the old process — LauncherActivity parks the outcome here instead.
    val orphanConfigResult = LocalOrphanWidgetConfigResult.current
    LaunchedEffect(orphanConfigResult) {
        orphanConfigResult?.collect { ok ->
            if (ok == null) return@collect
            orphanConfigResult.value = null
            val p = pendingWidget
            if (p != null) {
                if (ok) {
                    finishWidget(p)
                } else {
                    widgetHost?.deleteAppWidgetId(p.appWidgetId)
                    pendingWidget = null
                }
            }
        }
    }

    // Shared drag state spanning the workspace, dock and drawer (Launcher3-style drag layer/controller).
    // Created by LauncherShell and passed in so the app drawer can drag onto home; falls back to a
    // local one when HomeScreen is used standalone (previews/tests).
    SideEffect {
        dragController.dockItemCount = uiState.dockApps.size
        dragController.dockHasSpace = settings.dockEnabled && uiState.dockApps.size < settings.dockColumns
    }
    // A drag that starts moving dismisses the menu (so the menu and drop bar never coexist).
    LaunchedEffect(dragController.moving) {
        if (dragController.moving) menuTarget = null
    }
    // Pressing HOME (onNewIntent → homeSignals) returns to a clean home: dismiss every open popup /
    // overlay. Without this the long-press app menu and the empty-area options popup lingered until the
    // user pressed Back — a Compose Popup doesn't react to HOME on its own. (Workspace separately uses
    // homeSignals to scroll back to page 0; a SharedFlow feeds both collectors.)
    LaunchedEffect(homeSignals) {
        homeSignals.collect {
            menuTarget = null
            homeOptions = null
            renameTarget = null
            openFolderId = null
            showWidgetPicker = false
        }
    }

    // Notification badges (empty when disabled), and whether to render the count or a plain dot.
    val badges = if (settings.showNotificationDots) uiState.badges else emptyMap()
    val badgeShowCount = settings.notificationDotCount
    val badgeScale = settings.notificationDotScale

    // All app icons on home (workspace, dock, folders) honour the themed-icons setting and the
    // user's app-label text-size multiplier.
    CompositionLocalProvider(
        LocalThemedIcons provides settings.useThemedIcons,
        LocalIconPack provides settings.iconPackPackage,
        LocalAppLabelScale provides settings.appLabelTextScale,
        // Force the workspace left-to-right even in RTL locales (ar/he/fa/ur). The grid renders with
        // RTL-aware Modifier.offset {} but the drag/drop, drop-ring and popup-anchor math is all
        // absolute left-origin, so under RTL they disagree and a drop lands in the mirrored cell.
        // Pinning LTR keeps render + touch consistent; the drawer/settings keep their natural RTL.
        LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Root-level, Initial-pass swipe detector: a flick up/down anywhere on home (over icons,
            // folders, shortcuts or the dock — not just empty space) drives the drawer/notifications.
            .pixelHomeSwipe(
                swipeUpForDrawer = settings.swipeUpForDrawer,
                swipeDownForNotifications = settings.swipeDownForNotifications,
                dragController = dragController,
                onDrawerDrag = onDrawerDrag,
                onDrawerSettle = onDrawerSettle,
                onOpenNotifications = { NotificationShade.expand(context) },
                // Left-edge action: a right-drag on the leftmost page launches the configured app.
                leftEdgeEnabled = settings.leftSwipeAppKey.isNotBlank(),
                atLeftEdge = { dragController.currentPage == 0 },
                onLeftEdgeAction = viewModel::onLeftSwipe,
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (settings.showStatusBar) {
                val density = LocalDensity.current
                // The status-bar zone height: use the display-cutout band (≈ system status bar height,
                // and non-zero even when the system bar is hidden), with a sane fallback for no-cutout
                // devices. Lets the themed bar sit in the cutout band, beside a centred punch-hole.
                val statusZone = with(density) {
                    WindowInsets.displayCutout.getTop(this).toDp()
                }.coerceAtLeast(24.dp)
                // Height of the still-visible system status bar; reserved as internal top padding so the
                // themed bar's scrim can extend up behind it to the top edge (no gap on tall-status-bar
                // phones like the Pixel 10 Pro) while the glyphs sit below the system bar.
                val systemBarInset = with(density) {
                    WindowInsets.statusBars.getTop(this).toDp()
                }
                // Blank the themed bar whenever the system status bar sits (or is about to sit) on top of
                // it, so the two never overlap:
                //  - systemBarInset > 0: the system bar takes real layout space — a permanent show
                //    (freeform/desktop windowing, lock-task) or a frame of our own hide/show animation.
                //  - headsUpActive: a heads-up notification TRANSIENTLY reveals the system bar OVER our
                //    content; WindowManager deliberately withholds that reveal from WindowInsets to keep the
                //    layout stable (verified against AOSP), so it can't be seen via insets — we detect it
                //    from our own NotificationListener instead and blank the bar for the heads-up window.
                val suppressThemedBar =
                    settings.hideSystemStatusBar && (systemBarInset > 0.dp || headsUpActive)
                if (!suppressThemedBar) {
                    StatusBar(
                        // Only align to the camera cutout when we own the top zone (system bar hidden).
                        alignToCutout = settings.hideSystemStatusBar,
                        scrimAlpha = settings.statusBarScrimOpacity,
                        // When hidden we own the whole top zone (no inset, self-aligns to the cutout);
                        // otherwise reserve the system bar's height so the scrim fills it and glyphs clear it.
                        topInset = if (settings.hideSystemStatusBar) 0.dp else systemBarInset,
                        modifier = Modifier
                            .fillMaxWidth()
                            // When the system bar is hidden, occupy its zone at the very top (replacing it).
                            // Otherwise the scrim + topInset (inside StatusBar) handle the system-bar band, so
                            // no external statusBarsPadding here — that padding is what left the scrim gap.
                            .then(
                                if (settings.hideSystemStatusBar) Modifier.heightIn(min = statusZone)
                                else Modifier,
                            ),
                    )
                }
            }
            Workspace(
                pageCount = uiState.pageCount,
                columns = settings.homeColumns,
                rows = viewModel.rows,
                entries = uiState.entries,
                badges = badges,
                badgeShowCount = badgeShowCount,
                badgeScale = badgeScale,
                showLabels = settings.showHomeLabels,
                labelColor = Color(settings.appLabelColor),
                showPageIndicator = settings.showPageIndicator,
                locked = settings.desktopLocked,
                homeSignals = homeSignals,
                dragController = dragController,
                onAppClick = viewModel::launch,
                onAppMenu = { app, anchor, above -> menuTarget = AppMenuTarget(app, anchor, DragSource.Home, above) },
                onMove = viewModel::moveItem,
                onMoveFolder = viewModel::moveFolder,
                onMoveToDock = { app, index -> viewModel.moveToDock(app, index) },
                onRemoveFromHome = { viewModel.removeFromHome(it) },
                onOpenFolder = { openFolderId = it.id },
                onLaunchShortcut = { viewModel.launchShortcut(it) },
                onRemoveShortcut = { viewModel.removeShortcut(it) },
                onCreateFolder = { target, dropped -> viewModel.createFolder(target, dropped, defaultFolderName) },
                onAddToFolder = { app, folderId -> viewModel.addToFolder(app, folderId) },
                onEmptyAreaMenu = { anchor, above -> homeOptions = anchor to above },
                onRemoveWidget = { w ->
                    widgetHost?.deleteAppWidgetId(w.appWidgetId)
                    viewModel.removeWidget(w.rowId)
                },
                onRestorePendingWidget = { startRestoreWidget(it) },
                // A pending placeholder has no allocated host id yet, so just drop its row.
                onRemovePendingWidget = { viewModel.removeWidget(it) },
                onSetWidgetBounds = viewModel::setWidgetBounds,
                onReconfigureWidget = { id ->
                    val info = appWidgetManager.getAppWidgetInfo(id)
                    val configure = info?.configure
                    if (configure != null) {
                        runCatching {
                            reconfigureLauncher.launch(
                                android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                                    .setComponent(configure)
                                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                            )
                        }
                    }
                },
                widgetViews = widgetViews,
                widgetScrollableById = widgetScrollableById,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Status-bar inset is consumed by the StatusBar above when it's shown; otherwise the
                    // workspace takes it itself.
                    .then(if (settings.showStatusBar) Modifier else Modifier.statusBarsPadding())
                    // No horizontal padding: the grid spans the full width so a "full width" widget
                    // (spanX = columns at cellX 0) reaches both screen edges with no side gap. Icons
                    // shift outward by a negligible ~8dp.
                    .padding(vertical = 12.dp),
            )

            // Show the dock whenever it's enabled — even with no favorites yet — so a fresh install
            // can be populated by dragging apps onto it (there is no "add to dock" menu item).
            if (settings.dockEnabled) {
                Dock(
                    apps = uiState.dockApps,
                    badges = badges,
                    badgeShowCount = badgeShowCount,
                    badgeScale = badgeScale,
                    showLabels = settings.showDockLabels,
                    labelColor = Color(settings.appLabelColor),
                    backgroundAlpha = settings.dockBackgroundOpacity,
                    locked = settings.desktopLocked,
                    dragController = dragController,
                    onAppClick = viewModel::launch,
                    onReorder = viewModel::reorderDock,
                    onMoveToHome = { app, page, cellX, cellY -> viewModel.moveToHome(app, page, cellX, cellY) },
                    onRemoveFromDock = { viewModel.removeFromDock(it) },
                    onAppMenu = { app, anchor -> menuTarget = AppMenuTarget(app, anchor, DragSource.Dock, anchor.y > windowHeightPx / 2) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }

        // Drop-to-remove zone: a "Poista" pill at the top during any removable home drag (an app, or a
        // pinned shortcut via [localDragging]); dropping an icon on it removes it from home. Turns red
        // while the dragged icon is over it. Publishes its bounds for the drag's hit-test.
        if ((dragController.moving && (dragController.source == DragSource.Home || dragController.source == DragSource.Dock)) || dragController.localDragging) {
            val overRemove = dragController.isOverRemove(dragController.rootPosition)
            Surface(
                color = if (overRemove) Color(0xFFD32F2F) else Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    // Clear the themed status bar (when shown) so the "remove" pill doesn't overlap it.
                    .padding(top = if (settings.showStatusBar) 44.dp else 16.dp)
                    .onGloballyPositioned { dragController.removeBounds = it.boundsInRoot() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(LauncherIcons.Close),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.drag_remove),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        // First-run onboarding card: shown until Arkikeskus is the default home. One button opens the
        // system role dialog to set it as the default launcher (works for any fresh GitHub install).
        if (!isDefaultLauncher) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .statusBarsPadding()
                    .padding(horizontal = 28.dp),
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.onboarding_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        runCatching { setDefaultLauncher.launch(DefaultLauncher.requestIntent(context)) }
                    }) {
                        Text(stringResource(R.string.onboarding_set_default))
                    }
                }
            }
        }

        // The floating dragged icon is drawn by LauncherShell (above the workspace, dock and the app
        // drawer), so it can travel across all three surfaces.
    }

    val menu = menuTarget
    if (menu != null) {
        AppActionPopup(
            app = menu.app,
            anchor = menu.anchor,
            // Flip the popup above the icon when it's in the lower half (e.g. dock or a bottom-row
            // icon), so it never runs off the bottom of the screen. Decided where the anchor is built.
            preferAbove = menu.preferAbove,
            actions = listOf(
                PopupAction(stringResource(R.string.app_info), LauncherIcons.Info) { AppActions.openAppInfo(context, menu.app) },
                PopupAction(stringResource(R.string.rename), LauncherIcons.Edit) { renameTarget = menu.app },
                PopupAction(
                    stringResource(if (menu.source == DragSource.Dock) R.string.dock_remove else R.string.home_remove),
                    LauncherIcons.Close,
                ) {
                    if (menu.source == DragSource.Dock) viewModel.removeFromDock(menu.app)
                    else viewModel.removeFromHome(menu.app)
                },
                PopupAction(stringResource(R.string.uninstall), LauncherIcons.Delete) { AppActions.uninstall(context, menu.app) },
            ),
            onDismiss = { menuTarget = null },
            onPinShortcut = { item -> viewModel.pinShortcut(item) },
        )
    }

    homeOptions?.let { (anchor, above) ->
        IconMenuPopup(
            anchor = anchor,
            preferAbove = above,
            items = buildList {
                if (!settings.desktopLocked) {
                    add(IconMenuItem(R.drawable.ic_widgets, stringResource(R.string.home_options_widgets)) {
                        showWidgetPicker = true
                    })
                }
                add(IconMenuItem(R.drawable.ic_home_settings, stringResource(R.string.home_options_settings)) {
                    onOpenSettings()
                })
                add(IconMenuItem(R.drawable.ic_wallpaper, stringResource(R.string.home_options_wallpaper)) {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), null)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
            },
            onDismiss = { homeOptions = null },
        )
    }

    if (showWidgetPicker) {
        WidgetPickerScreen(
            onPick = { provider ->
                showWidgetPicker = false
                startAddWidget(provider)
            },
            onDismiss = { showWidgetPicker = false },
            modifier = Modifier.fillMaxSize(),
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

/**
 * Root-level home swipe-up/down detector — the Pixel-style fix for "the drawer swipe only catches on
 * empty space". Applied to HomeScreen's root Box and run in [PointerEventPass.Initial] (parent before
 * children), so it sees movement *before* the icons, folders, shortcuts, dock and the HorizontalPager.
 *
 * It does NOT consume the DOWN and only consumes once the gesture is clearly vertical past the touch
 * slop — so taps, long-presses and horizontal page swipes pass through untouched, while a flick up
 * from anywhere on the home surface reliably "catches" and drives the drawer (the root cause of the
 * old bug: the detector lived on the page background, so icons/dock won the gesture first).
 *
 * - Swipe up (with [swipeUpForDrawer]) → finger-following drawer via [onDrawerDrag] / [onDrawerSettle].
 * - Swipe down (with [swipeDownForNotifications]) → [onOpenNotifications] (one-shot).
 * - A real long-press drag — an app/dock item ([HomeDragController.isDragging]) or a folder/shortcut
 *   ([HomeDragController.localGestureActive] / [HomeDragController.localDragging]) — is never stolen;
 *   the detector bails out so the drag owns the gesture.
 *
 * The velocity tracker is fed the UP position *before* `calculateVelocity()` so a fast flick reports
 * its true release speed (the old detector computed velocity without the final sample, which made fast
 * flicks under-settle and snap shut).
 */
@Composable
private fun Modifier.pixelHomeSwipe(
    swipeUpForDrawer: Boolean,
    swipeDownForNotifications: Boolean,
    dragController: HomeDragController,
    onDrawerDrag: (Float) -> Unit,
    onDrawerSettle: (Float) -> Unit,
    onOpenNotifications: () -> Unit,
    leftEdgeEnabled: Boolean = false,
    atLeftEdge: () -> Boolean = { false },
    onLeftEdgeAction: () -> Unit = {},
): Modifier {
    val latestOnDrawerDrag by rememberUpdatedState(onDrawerDrag)
    val latestOnDrawerSettle by rememberUpdatedState(onDrawerSettle)
    val latestOnOpenNotifications by rememberUpdatedState(onOpenNotifications)
    val latestOnLeftEdgeAction by rememberUpdatedState(onLeftEdgeAction)

    return pointerInput(swipeUpForDrawer, swipeDownForNotifications, leftEdgeEnabled, dragController) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // A touch that starts inside a scrollable collection widget (e.g. WhatsApp's chat list)
            // belongs to that widget's own list — bail without consuming so the scroll falls through to
            // the embedded AppWidgetHostView instead of the swipe-up stealing the vertical drag.
            if (dragController.isOverScrollableWidget(down.position)) return@awaitEachGesture
            // A touch that starts on the dock belongs to the dock's own gestures (tap / long-press for
            // the shortcut popup / drag to reorder or onto home). Bail so the dock owns it end-to-end —
            // otherwise this Initial-pass detector steals a small upward drift during a dock long-press,
            // cancelling the popup and half-opening the drawer (the "2/10 on Samsung" flakiness). Swipe
            // up to open the drawer still works from anywhere above the dock.
            if (dragController.isOverDock(down.position)) return@awaitEachGesture
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            // 0 = undecided, 1 = drawer up-drag, 2 = left-edge action (right-drag on page 0),
            // 3 = notification shade opened (swallow the rest of the gesture so it can't reach the pager)
            var mode = 0
            var lastY = down.position.y

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocityTracker.addPosition(change.uptimeMillis, change.position)

                // Never steal a real long-press drag (an app/dock item, or a folder/shortcut).
                if (dragController.isDragging ||
                    dragController.localGestureActive ||
                    dragController.localDragging
                ) {
                    break
                }

                if (!change.pressed) {
                    if (mode == 1) {
                        val finalDy = change.position.y - lastY
                        if (finalDy != 0f) latestOnDrawerDrag(finalDy)
                        latestOnDrawerSettle(velocityTracker.calculateVelocity().y)
                    } else if (mode == 2) {
                        // Left-edge action: commit only when dragged far enough right (a short drag
                        // cancels, so the gesture can be aborted by releasing early).
                        if (change.position.x - down.position.x > size.width * 0.25f) latestOnLeftEdgeAction()
                    }
                    break
                }

                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y

                if (mode == 0) {
                    when {
                        abs(dy) > touchSlop && abs(dy) > abs(dx) -> when {
                            dy < 0f && swipeUpForDrawer -> {
                                mode = 1
                                change.consume()
                                latestOnDrawerDrag(dy) // jump to the finger, including the slop moved
                                lastY = change.position.y
                            }
                            dy > 0f && swipeDownForNotifications -> {
                                // Open the shade, then keep consuming the rest of the gesture (mode 3)
                                // until the finger lifts. Without this, the finger's continued sideways
                                // drift after the shade opens leaks to the HorizontalPager, which nudges
                                // a page and snaps back — the intermittent "little jump sideways".
                                mode = 3
                                change.consume()
                                latestOnOpenNotifications()
                            }
                            else -> {
                                break
                            }
                        }
                        // Horizontal gesture.
                        abs(dx) > touchSlop && abs(dx) >= abs(dy) -> {
                            if (leftEdgeEnabled && dx > 0f && atLeftEdge()) {
                                // Right-drag on the leftmost page → the configurable left-edge action.
                                // Consume so the pager doesn't overscroll; commit on release past slop.
                                mode = 2
                                change.consume()
                            } else {
                                // Other horizontal → don't consume; leave it to the HorizontalPager.
                                break
                            }
                        }
                    }
                } else if (mode == 1) {
                    change.consume()
                    latestOnDrawerDrag(change.position.y - lastY)
                    lastY = change.position.y
                } else {
                    // mode == 2 (left-edge drag) or mode == 3 (shade opened): keep consuming so the
                    // pager never grabs the rest of the gesture.
                    change.consume()
                }
            }
        }
    }
}

/** In-flight widget bind/configure state. [restoreRowId] non-null = re-binding a restored placeholder
 *  row (from a backup) rather than adding a new widget. */
private data class PendingWidgetBind(
    val appWidgetId: Int,
    val provider: AppWidgetProviderInfo,
    val restoreRowId: Long?,
)

/** Bundles [PendingWidgetBind] into saved instance state so an in-flight bind survives process death
 *  while the system bind dialog or the widget's configuration activity is in the foreground. */
private val PendingWidgetBindSaver = Saver<PendingWidgetBind?, android.os.Bundle>(
    save = { p ->
        p?.let {
            android.os.Bundle().apply {
                putInt("id", it.appWidgetId)
                putParcelable("provider", it.provider)
                putLong("row", it.restoreRowId ?: -1L)
            }
        }
    },
    restore = { b ->
        @Suppress("DEPRECATION")
        val provider = b.getParcelable<AppWidgetProviderInfo>("provider")
        provider?.let {
            PendingWidgetBind(b.getInt("id"), it, b.getLong("row").takeIf { row -> row >= 0 })
        }
    },
)

/** The long-pressed app and where its menu should anchor (which surface it came from). */
private data class AppMenuTarget(
    val app: AppItem,
    val anchor: IntOffset,
    val source: DragSource,
    val preferAbove: Boolean,
)

/** The long-press menu is the shared [org.arkikeskus.launcher.ui.AppActionPopup] (core/ui). */
