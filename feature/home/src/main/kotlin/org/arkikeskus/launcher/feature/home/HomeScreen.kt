package org.arkikeskus.launcher.feature.home

import kotlinx.coroutines.flow.MutableStateFlow
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.arkikeskus.launcher.data.PendingWidgetIds
import org.arkikeskus.launcher.data.sweepHostIds
import org.arkikeskus.launcher.model.WidgetPlacement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppActionPopup
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.DefaultLauncher
import org.arkikeskus.launcher.ui.DismissWhenDragMoves
import org.arkikeskus.launcher.ui.DragSource
import org.arkikeskus.launcher.ui.EditAppDialog
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.IconMenuItem
import org.arkikeskus.launcher.ui.IconMenuPopup
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.LocalAppLaunchSources
import org.arkikeskus.launcher.ui.PopupAction
import org.arkikeskus.launcher.ui.R as UiR
import org.arkikeskus.launcher.ui.rememberHomeDragController
import org.arkikeskus.launcher.ui.component.LocalAppLabelLines
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.arkikeskus.launcher.ui.component.LocalIconScale
import org.arkikeskus.launcher.ui.component.LocalIconPack
import org.arkikeskus.launcher.ui.component.LocalThemedIcons
import kotlin.math.abs

/** The space above and below the workspace in the home column. */
internal val WORKSPACE_VERTICAL_PADDING = 12.dp

/** The space around the dock in the home column. */
internal val DOCK_OUTER_PADDING = 14.dp

/** The themed status bar's least height with the system bar hidden (the cutout's band, when deeper). */
internal val STATUS_ZONE_MIN = 24.dp

/**
 * The workspace's place in the home column: the themed status bar above takes the system bar's inset
 * when it is shown, otherwise the workspace does. No horizontal padding: the grid spans the full width
 * so a "full width" widget (spanX = columns at cellX 0) reaches both screen edges with no side gap.
 */
internal fun Modifier.homeWorkspacePadding(showStatusBar: Boolean): Modifier =
    then(if (showStatusBar) Modifier else Modifier.statusBarsPadding()).padding(vertical = WORKSPACE_VERTICAL_PADDING)

/** The dock's place in the home column: above the navigation bar, with room all round. */
internal fun Modifier.homeDockPadding(): Modifier = navigationBarsPadding().padding(DOCK_OUTER_PADDING)

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
    homeSignals: Flow<Boolean> = emptyFlow(),
    onDrawerDrag: (Float) -> Unit = {},
    onDrawerSettle: (Float) -> Unit = {},
    /** The app drawer is open or sliding: the workspace is not at rest (see [Workspace]'s newItems). */
    drawerOpen: Boolean = false,
    dragController: HomeDragController = rememberHomeDragController(),
    /** A package whose widgets another surface (the drawer's popup) asks to see: opens the picker
     *  on that app's widgets, then [onShowWidgetsHandled] clears the request. */
    showWidgetsFor: String? = null,
    onShowWidgetsHandled: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    // True briefly after a heads-up notification, while the system transiently shows its own status bar
    // over ours; used to blank the themed bar so they don't overlap.
    val headsUpActive by viewModel.headsUpActive.collectAsStateWithLifecycle()
    // First-run intro (fresh installs only) — covers the whole home surface until finished/skipped.
    val showOnboarding by viewModel.showOnboarding.collectAsStateWithLifecycle()
    val leftSwipeAvailable by viewModel.leftSwipeAvailable.collectAsStateWithLifecycle()
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

    // READ_PHONE_STATE granted outside onboarding (Settings ▸ Luvat, system app info) has no callback
    // into SignalMonitor — re-check on every resume so the 4G/5G generation label appears when the
    // user comes back home. No-op when the grant state hasn't changed.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPhonePermission()
        onPauseOrDispose { }
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
    // A tapped app opens with a zoom from its icon (AppLaunchSources).
    val launchSources = LocalAppLaunchSources.current
    val launchApp: (AppItem) -> Unit = { app -> viewModel.launch(app, launchSources?.take(app)) }
    // An icon's menu opens as the icon lifts; pulling the icon on closes it (issue #12).
    DismissWhenDragMoves(dragController) { menuTarget = null }
    var renameTarget by remember { mutableStateOf<AppItem?>(null) }
    var openFolderId by remember { mutableStateOf<Long?>(null) }
    // Empty-area long-press options popup (anchor + whether it flips above the press point).
    var homeOptions by remember { mutableStateOf<Pair<IntOffset, Boolean>?>(null) }
    // Page the workspace should show after a page was added or removed from the empty-area menu.
    val pageRequests = remember { MutableSharedFlow<Int>(extraBufferCapacity = 1) }
    val defaultFolderName = stringResource(UiR.string.folder_default_name)

    val widgetHost = LocalAppWidgetHost.current
    val widgetConfigLauncher = LocalWidgetConfigLauncher.current
    val widgetReconfigureLauncher = LocalWidgetReconfigureLauncher.current
    // Bumped when the system reports a provider change: host views that could not be created (the
    // provider info was missing while its app was being updated) are tried again.
    val widgetProviderGeneration by (
        (widgetHost as? LauncherWidgetHost)?.providerGeneration ?: remember { MutableStateFlow(0) }
        ).collectAsStateWithLifecycle()
    val appWidgetManager = remember { AppWidgetManager.getInstance(context) }
    var showWidgetPicker by remember { mutableStateOf(false) }
    // The package an icon's Widgets row opened the picker on; null for the full gallery.
    var widgetPickerPackage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showWidgetsFor) {
        val pkg = showWidgetsFor ?: return@LaunchedEffect
        widgetPickerPackage = pkg
        showWidgetPicker = true
        onShowWidgetsHandled()
    }
    val widgetDrag = remember(dragController) { WidgetDragController(dragController) }
    val widgetScope = rememberCoroutineScope()
    var liveBindId by remember { mutableStateOf<Int?>(null) }
    fun widgetMessage(message: Int) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    // What the ViewModel could not do for the user (e.g. the home screen is full), shown once each.
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { widgetMessage(it.message) }
    }

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
    val widgetSizeReporter = remember { WidgetSizeReporter() }
    // Frees AppWidgetHost ids that no home_items row holds. The host ids AND the in-flight bind are
    // snapshotted BEFORE the (suspending) DB query: an id allocated, or a bind finished, while the
    // query runs is absent from the snapshot or excluded, so it can never be swept. An id another
    // app's widget request holds (PendingWidgetIds: its bind dialog may be open right now) is left
    // alone too, and the whole sweep — with [then], the startup's reverse direction — runs under the
    // request's guard, so a request can't allocate or store an id half-way through it. Returns the
    // snapshot, or null when the host can't be read.
    suspend fun sweepUnusedHostIds(
        host: android.appwidget.AppWidgetHost,
        then: suspend (Set<Int>) -> Unit = {},
    ): Set<Int>? = PendingWidgetIds.shared.sweepHostIds(
        hostIds = { runCatching { host.appWidgetIds }.getOrNull() },
        rowIds = { viewModel.boundWidgetIds() },
        keep = { setOfNotNull(pendingWidget?.appWidgetId) },
        delete = { id -> runCatching { host.deleteAppWidgetId(id) } },
        then = then,
    )
    val placedWidgetIds = remember(uiState.entries) {
        uiState.entries.filterIsInstance<PlacedWidget>().map { it.appWidgetId }.toSet()
    }
    LaunchedEffect(placedWidgetIds, widgetHost, widgetProviderGeneration) {
        val host = widgetHost ?: return@LaunchedEffect
        placedWidgetIds.forEach { id ->
            if (widgetViews[id] == null) {
                val info = appWidgetManager.getAppWidgetInfo(id)
                if (info != null) {
                    runCatching {
                        // Mark the widget as living on the HOME screen up front — some providers
                        // (WhatsApp's chat list) won't populate until the host category is set. Only
                        // when it isn't set yet: every update is a broadcast the provider redraws on.
                        val category = appWidgetManager.getAppWidgetOptions(id)
                            .getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, 0)
                        if (category != AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) {
                            appWidgetManager.updateAppWidgetOptions(
                                id,
                                android.os.Bundle().apply {
                                    putInt(
                                        AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                                    )
                                },
                            )
                        }
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
        val gone = widgetViews.keys - placedWidgetIds
        gone.forEach {
            widgetViews.remove(it)
            widgetScrollableById.remove(it)
            widgetSizeReporter.forget(it)
        }
        // Rows that vanished (a restore replaced the layout, a provider was uninstalled) left their
        // host ids allocated until the next start, and providers kept updating widgets nobody shows.
        if (gone.isNotEmpty()) sweepUnusedHostIds(host)
    }

    // One-shot at startup: free any AppWidgetHost ids with no home_items row — e.g. the old bound ids
    // left over after a full backup restore replaced the layout — so the host doesn't leak widget ids.
    LaunchedEffect(widgetHost) {
        val host = widgetHost ?: return@LaunchedEffect
        // The reverse direction: DB rows bound to ids this host never allocated — what a Google Auto
        // Backup / device-transfer restore leaves behind (the Room DB carries the OLD device's ids).
        // Unbind them into tap-to-set-up placeholders instead of invisible dead zones on the grid.
        sweepUnusedHostIds(host) { hostIds ->
            val validIds = hostIds.toMutableSet()
            pendingWidget?.appWidgetId?.let { validIds.add(it) }
            viewModel.unbindStaleWidgets(validIds)
        }
    }

    fun finishWidget(bind: PendingWidgetBind) {
        widgetScope.launch {
            // The allocated id and the layout row must reach a consistent state even if the Activity
            // is destroyed while Room is writing. Process death is recovered by the saved bind state.
            withContext(NonCancellable) {
                val saved = runCatching {
                    if (bind.restoreRowId != null) {
                        viewModel.bindRestoredWidget(bind.restoreRowId, bind.appWidgetId)
                    } else {
                        val target = bind.placement ?: return@runCatching false
                        viewModel.addWidgetAt(bind.appWidgetId, bind.provider.provider.flattenToString(), null, target)
                    }
                }.getOrDefault(false)
                if (!saved) {
                    runCatching { widgetHost?.deleteAppWidgetId(bind.appWidgetId) }
                    widgetMessage(R.string.widget_add_failed)
                }
                if (pendingWidget?.appWidgetId == bind.appWidgetId) pendingWidget = null
            }
        }
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
    fun startBind(provider: AppWidgetProviderInfo, restoreRowId: Long?, placement: WidgetPlacement? = null) {
        // Guards a double tap. Only a bind started by THIS process blocks: [pendingWidget] is saveable,
        // so one whose result never arrives would otherwise refuse every later add, silently, forever.
        if (pendingWidget != null && pendingWidget?.appWidgetId == liveBindId) return
        val host = widgetHost ?: return
        val id = runCatching { host.allocateAppWidgetId() }.getOrElse {
            widgetMessage(R.string.widget_add_failed)
            return
        }
        val bind = PendingWidgetBind(id, provider, restoreRowId, placement)
        liveBindId = id
        pendingWidget = bind
        val bound = runCatching { appWidgetManager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, null) }.getOrDefault(false)
        if (bound) {
            configureOrFinish(bind)
        } else {
            pendingWidget = bind
            val intent = android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
            }
            runCatching { bindLauncher.launch(intent) }
                .onFailure { host.deleteAppWidgetId(id); pendingWidget = null }
        }
    }

    fun addWidget(choice: WidgetChoice, placement: WidgetPlacement) {
        showWidgetPicker = false
        when (choice) {
            is WidgetChoice.App -> startBind(choice.provider, restoreRowId = null, placement = placement)
            is WidgetChoice.Builtin -> widgetScope.launch {
                val ok = runCatching { viewModel.addWidgetAt(null, null, choice.type, placement) }.getOrDefault(false)
                if (!ok) widgetMessage(R.string.widget_add_failed)
            }
        }
    }

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
    // overlay — on every signal, also when coming home from an app. Without this the long-press app
    // menu and the empty-area options popup lingered until the user pressed Back — a Compose Popup
    // doesn't react to HOME on its own. (Workspace separately uses homeSignals to scroll back to
    // page 0, but only on an alreadyOnHome press; a SharedFlow feeds both collectors.)
    LaunchedEffect(homeSignals) {
        homeSignals.collect {
            menuTarget = null
            homeOptions = null
            renameTarget = null
            openFolderId = null
            showWidgetPicker = false
            widgetDrag.cancel()
        }
    }

    // Notification badges (empty when disabled), and whether to render the count or a plain dot.
    val badges = if (settings.showNotificationDots) uiState.badges else emptyMap()
    val badgeShowCount = settings.notificationDotCount
    val badgeScale = settings.notificationDotScale

    // All app icons on home (workspace, dock, folders) honour the themed-icons setting and the
    // user's app-label text-size multiplier.
    CompositionLocalProvider(
        LocalTonalWidgets provides settings.widgetTonalBackground,
        LocalThemedIcons provides settings.useThemedIcons,
        LocalIconPack provides settings.iconPackPackage,
        LocalAppLabelScale provides settings.appLabelTextScale,
        LocalIconScale provides settings.iconScale,
        LocalAppLabelLines provides if (settings.twoLineHomeLabels) 2 else 1,
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
                // All home gestures pause while the first-run intro covers the screen — this
                // detector runs in the Initial pass on the ROOT, so it would otherwise catch a
                // vertical swipe before the intro overlay could swallow it.
                // Paused likewise while a folder is open: the card is composed inside this Box, so a
                // scroll of its grid, or a vertical slip before a hold completes, would otherwise
                // open the drawer or the shade through it.
                swipeUpForDrawer = settings.swipeUpForDrawer && !showOnboarding && openFolderId == null,
                swipeDownForNotifications = settings.swipeDownForNotifications && !showOnboarding && openFolderId == null,
                dragController = dragController,
                onDrawerDrag = onDrawerDrag,
                onDrawerSettle = onDrawerSettle,
                onOpenNotifications = { NotificationShade.expand(context) },
                // Left-edge action: a right-drag on the leftmost page launches the configured app.
                leftEdgeEnabled = leftSwipeAvailable && !showOnboarding && openFolderId == null,
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
                }.coerceAtLeast(STATUS_ZONE_MIN)
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
                StatusBar(
                    // Only align to the camera cutout when we own the top zone (system bar hidden).
                    alignToCutout = settings.hideSystemStatusBar,
                    scrimAlpha = settings.statusBarScrimOpacity,
                    // When hidden we own the whole top zone (no inset, self-aligns to the cutout);
                    // otherwise reserve the system bar's height so the scrim fills it and glyphs clear it.
                    topInset = if (settings.hideSystemStatusBar) 0.dp else systemBarInset,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Blank with alpha, NOT by leaving composition: the bar is the Column sibling
                        // that pushes the workspace down, so removing it would jump the whole grid up
                        // for the heads-up window and back — and let the top row sit under a
                        // force-shown system bar. Only the pixels must vanish; the slot stays.
                        .graphicsLayer { alpha = if (suppressThemedBar) 0f else 1f }
                        // When the system bar is hidden, occupy its zone at the very top (replacing it).
                        // Otherwise the scrim + topInset (inside StatusBar) handle the system-bar band, so
                        // no external statusBarsPadding here — that padding is what left the scrim gap.
                        .then(
                            if (settings.hideSystemStatusBar) Modifier.heightIn(min = statusZone)
                            else Modifier,
                        ),
                )
            }
            val lockNeedsServiceMsg = stringResource(R.string.double_tap_lock_needs_service)
            Workspace(
                pageCount = uiState.pageCount,
                columns = settings.homeColumns,
                rows = settings.homeRows,
                entries = uiState.entries,
                badges = badges,
                badgeShowCount = badgeShowCount,
                badgeScale = badgeScale,
                showLabels = settings.showHomeLabels,
                labelColor = Color(settings.appLabelColor),
                showPageIndicator = settings.showPageIndicator,
                homePageMarker = settings.homePageMarker,
                infiniteScroll = settings.infiniteScroll,
                // Same condition as pixelHomeSwipe's left-edge gesture above: while it owns the
                // right-drag on page 0, page 0 must not wrap back.
                leftEdgeActionEnabled = leftSwipeAvailable && !showOnboarding,
                locked = settings.desktopLocked,
                homeSignals = homeSignals,
                homePage = if (uiState.loaded) uiState.homePage else null,
                pageRequests = pageRequests,
                dragController = dragController,
                widgetDragController = widgetDrag,
                onAddWidget = ::addWidget,
                onAppClick = launchApp,
                onAppMenu = { app, anchor, above -> menuTarget = AppMenuTarget(app, anchor, DragSource.Home, above) },
                onMove = viewModel::moveItem,
                onMoveFolder = viewModel::moveFolder,
                onMoveToDock = { app, index -> viewModel.moveToDock(app, index) },
                onRemoveFromHome = { viewModel.removeFromHome(it) },
                onOpenFolder = { openFolderId = it.id },
                onLaunchShortcut = { viewModel.launchShortcut(it) },
                onRemoveShortcut = { viewModel.removeShortcut(it) },
                onRemoveFolder = { viewModel.removeFolder(it) },
                onCreateFolder = { target, dropped -> viewModel.createFolder(target, dropped, defaultFolderName) },
                onAddToFolder = { app, folderId -> viewModel.addToFolder(app, folderId) },
                onEmptyAreaMenu = { anchor, above -> homeOptions = anchor to above },
                onEmptyAreaDoubleTap = {
                    // `settings` is this composition's value; Workspace reads the latest lambda via
                    // rememberUpdatedState, so a toggled setting takes effect without a restart.
                    if (settings.doubleTapToLock && !LockAccessibilityService.lock()) {
                        android.widget.Toast.makeText(
                            context,
                            lockNeedsServiceMsg,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onRemoveWidget = { rowId, appWidgetId ->
                    // A built-in widget has no host id to free (appWidgetId == null).
                    appWidgetId?.let { widgetHost?.deleteAppWidgetId(it) }
                    viewModel.removeWidget(rowId)
                },
                onRestorePendingWidget = { startRestoreWidget(it) },
                // A pending placeholder has no allocated host id yet, so just drop its row.
                onRemovePendingWidget = { viewModel.removeWidget(it) },
                onSetWidgetBounds = viewModel::setWidgetBounds,
                onReconfigureWidget = { id ->
                    val info = appWidgetManager.getAppWidgetInfo(id)
                    val configure = info?.configure
                    if (configure != null) {
                        // Through the host first: the system starts the activity on the provider's
                        // behalf, so one that is not exported opens too (a raw intent is refused
                        // there, and nothing opened). The raw intent stays as the fallback.
                        val throughHost = widgetReconfigureLauncher?.invoke(id) == true
                        if (!throughHost) {
                            runCatching {
                                reconfigureLauncher.launch(
                                    android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                                        .setComponent(configure)
                                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                                )
                            }
                        }
                    }
                },
                widgetViews = widgetViews,
                widgetProviderGeneration = widgetProviderGeneration,
                widgetScrollableById = widgetScrollableById,
                widgetSizeReporter = widgetSizeReporter,
                drawerOpen = drawerOpen,
                chrome = workspaceChrome(settings, uiState.pageCount, LocalDensity.current.fontScale, uiState.dockApps.size),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .homeWorkspacePadding(settings.showStatusBar),
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
                    onAppClick = launchApp,
                    onReorder = viewModel::reorderDock,
                    onMoveToHome = { app, page, cellX, cellY -> viewModel.moveToHome(app, page, cellX, cellY, defaultFolderName) },
                    onRemoveFromDock = { viewModel.removeFromDock(it) },
                    onAppMenu = { app, anchor -> menuTarget = AppMenuTarget(app, anchor, DragSource.Dock, anchor.y > windowHeightPx / 2) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .homeDockPadding(),
                )
            }
        }

        // The open folder: a card in this window (so a drag can leave it for the home surfaces),
        // composed before the remove pill so the pill draws over the fading card.
        val openFolder = openFolderId?.let { id ->
            uiState.entries.filterIsInstance<PlacedFolder>().firstOrNull { it.id == id }
        }
        // Close the card if the folder dissolved (dropped to one app) while it was open.
        LaunchedEffect(openFolderId, openFolder == null) {
            if (openFolderId != null && openFolder == null) openFolderId = null
        }
        if (openFolder != null) {
            FolderCard(
                folder = openFolder,
                badges = badges,
                badgeShowCount = badgeShowCount,
                badgeScale = badgeScale,
                locked = settings.desktopLocked,
                dragController = dragController,
                onRename = { viewModel.renameFolder(openFolder.id, it) },
                onSetIcon = { viewModel.setFolderIcon(openFolder.id, it) },
                onAppClick = launchApp,
                onRenameApp = { renameTarget = it },
                onRemoveFromFolder = { viewModel.removeFromFolder(it, openFolder.id) },
                onPinShortcut = { viewModel.pinShortcut(it) },
                onReorder = { app, before -> viewModel.reorderInFolder(openFolder.id, app, before) },
                onDropOnHome = { app, page, cx, cy -> viewModel.moveOutOfFolder(app, openFolder.id, page, cx, cy, defaultFolderName) },
                onDropOnDock = { viewModel.moveFolderAppToDock(it, openFolder.id) },
                onDropOnRemove = { viewModel.removeFromFolderEntirely(it, openFolder.id) },
                // Whatever the drop was, a drag that left the card closed the folder.
                onDragOutEnd = { openFolderId = null },
                // The card would cover the picker: close it first.
                onWidgets = if (settings.desktopLocked) null else ({ app ->
                    openFolderId = null
                    widgetPickerPackage = app.packageName
                    showWidgetPicker = true
                }),
                onDismiss = { openFolderId = null },
            )
        }

        // Drop-to-remove zone: a "Poista" pill at the top during any removable home drag (an app, or a
        // pinned shortcut via [localDragging]); dropping an icon on it removes it from home. Turns red
        // while the dragged icon is over it. Publishes its bounds for the drag's hit-test.
        // derivedStateOf: reading the finger position directly here recomposed the whole home screen
        // (status bar, workspace, dock) on every pointer frame of every drag.
        val overRemove by remember(dragController) {
            derivedStateOf { dragController.isOverRemove(dragController.rootPosition) }
        }
        if (dragController.isIncomingFrom(DragSource.Home, DragSource.Dock, DragSource.Folder) || dragController.localDragging) {
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
        // Not-default reminder card: shown until ARK-launcher is the default home or the user says
        // "Not now" (Settings keeps the set-as-default row) — but never under the full first-run
        // intro, which has its own set-as-default step, nor over an open folder's card.
        if (showDefaultReminder(uiState.loaded, isDefaultLauncher, showOnboarding, settings.defaultReminderDismissed, openFolderId != null)) {
            DefaultLauncherReminder(
                onSetDefault = {
                    runCatching { setDefaultLauncher.launch(DefaultLauncher.requestIntent(context)) }
                },
                onNotNow = viewModel::dismissDefaultReminder,
                modifier = Modifier
                    .align(Alignment.Center)
                    .statusBarsPadding()
                    .padding(horizontal = 28.dp),
            )
        }

        // The floating dragged icon is drawn by LauncherShell (above the workspace, dock and the app
        // drawer), so it can travel across all three surfaces.

        // First-run intro on top of everything (fresh installs only, once).
        if (showOnboarding) {
            OnboardingFlow(
                onFinish = { viewModel.finishOnboarding() },
                onContactsGranted = { viewModel.onContactsPermissionGranted() },
                onPhoneGranted = { viewModel.onPhonePermissionGranted() },
                modifier = Modifier.fillMaxSize(),
            )
        }
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
            onWidgets = if (settings.desktopLocked) null else ({
                widgetPickerPackage = menu.app.packageName
                showWidgetPicker = true
            }),
            // Opened at the lift, under the still-held finger (issue #12).
            held = dragController.isDragging,
        )
    }

    homeOptions?.let { (anchor, above) ->
        IconMenuPopup(
            anchor = anchor,
            preferAbove = above,
            items = buildList {
                if (!settings.desktopLocked) {
                    add(IconMenuItem(LauncherIcons.Widgets, stringResource(R.string.home_options_widgets)) {
                        widgetPickerPackage = null
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
                // Pages: the menu was opened on the page the pager is showing.
                val page = dragController.currentPage.coerceIn(0, uiState.pageCount - 1)
                fun showInsertedPage(at: Int) = when {
                    at >= 0 -> { pageRequests.tryEmit(at); Unit }
                    at == PAGE_FAILED -> widgetMessage(R.string.home_options_page_failed)
                    else -> widgetMessage(R.string.home_options_page_limit)
                }
                if (!settings.desktopLocked) {
                    add(IconMenuItem(R.drawable.ic_page_add_left, stringResource(R.string.home_options_page_left)) {
                        widgetScope.launch { showInsertedPage(viewModel.insertPage(page)) }
                    })
                    add(IconMenuItem(R.drawable.ic_page_add_right, stringResource(R.string.home_options_page_right)) {
                        widgetScope.launch { showInsertedPage(viewModel.insertPage(page + 1)) }
                    })
                }
                if (page != uiState.homePage) {
                    add(IconMenuItem(R.drawable.ic_home_page, stringResource(R.string.home_options_set_home)) {
                        viewModel.setHomePage(page)
                    })
                }
                if (!settings.desktopLocked && uiState.pageCount > 1 && viewModel.isPageEmpty(page)) {
                    add(IconMenuItem(LauncherIcons.Delete, stringResource(R.string.home_options_page_remove)) {
                        widgetScope.launch {
                            when (val target = viewModel.removeEmptyPage(page)) {
                                null -> widgetMessage(R.string.home_options_page_not_empty)
                                PAGE_FAILED -> widgetMessage(R.string.home_options_page_failed)
                                else -> pageRequests.tryEmit(target)
                            }
                        }
                    })
                }
            },
            onDismiss = { homeOptions = null },
        )
    }

    if (showWidgetPicker) {
        CompositionLocalProvider(LocalTonalWidgets provides settings.widgetTonalBackground) {
        WidgetPickerScreen(
            dragController = widgetDrag,
            columns = settings.homeColumns,
            rows = settings.homeRows,
            onPick = { choice, sx, sy ->
                val target = widgetDrag.findSpace?.invoke(sx, sy)
                if (target == null) widgetMessage(R.string.widget_no_space) else addWidget(choice, target)
            },
            onDismiss = { widgetDrag.cancel(); showWidgetPicker = false; widgetPickerPackage = null },
            modifier = Modifier.fillMaxSize(),
            onlyPackage = widgetPickerPackage,
        )
        }
    }

    renameTarget?.let { app ->
        EditAppDialog(
            app = app,
            onSave = { name, icon -> viewModel.editApp(app, name, icon) },
            onReset = { viewModel.resetApp(app) },
            onDismiss = { renameTarget = null },
        )
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
            var settled = false

            // A drawer drag that ends any other way than a finger-up (a lift by another finger, the
            // pointer vanishing, the gesture cancelled) must still settle, or the drawer stayed frozen
            // half-open until Back or HOME.
            try { while (true) {
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
                        settled = true
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
            } } finally {
                if (mode == 1 && !settled) latestOnDrawerSettle(0f)
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
    val placement: WidgetPlacement? = null,
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
                it.placement?.let { p ->
                    putIntArray("placement", intArrayOf(p.page, p.cellX, p.cellY, p.spanX, p.spanY))
                }
            }
        }
    },
    restore = { b ->
        @Suppress("DEPRECATION")
        val provider = b.getParcelable<AppWidgetProviderInfo>("provider")
        provider?.let {
            PendingWidgetBind(b.getInt("id"), it, b.getLong("row").takeIf { row -> row >= 0 },
                b.getIntArray("placement")?.takeIf { p -> p.size == 5 }?.let { p ->
                    WidgetPlacement(p[0], p[1], p[2], p[3], p[4])
                })
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

/** The card on the home screen while ARK-launcher is not the default home app. */
@Composable
internal fun DefaultLauncherReminder(
    onSetDefault: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 8.dp,
        modifier = modifier,
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
            Button(onClick = onSetDefault) {
                Text(stringResource(R.string.onboarding_set_default))
            }
            // Issue #6: the card covered the middle of home with no way to close it.
            TextButton(onClick = onNotNow) {
                Text(stringResource(R.string.default_reminder_not_now))
            }
        }
    }
}

/**
 * Whether the set-as-default card shows: only once the home state has [loaded] (until then the
 * stored "dismissed" still reads its default, and a dismissed card flashed at every cold start), while
 * ARK-launcher is not the default home, the first-run intro is not up, and the user has not said
 * "Not now" — and not while a folder is open ([folderOpen]): its card sits at the screen's centre too,
 * and the reminder, composed later, drew over it and took its taps.
 */
internal fun showDefaultReminder(
    loaded: Boolean, isDefault: Boolean, showOnboarding: Boolean, dismissed: Boolean, folderOpen: Boolean,
): Boolean = loaded && !isDefault && !showOnboarding && !dismissed && !folderOpen
