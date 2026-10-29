package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.res.Configuration
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.data.PinSpans
import org.arkikeskus.launcher.launcher.system.BatteryMonitor
import org.arkikeskus.launcher.launcher.system.ConnectivityMonitor
import org.arkikeskus.launcher.launcher.system.SignalMonitor
import org.arkikeskus.launcher.launcher.system.SystemFlagsMonitor
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.HomeDragController
import org.arkikeskus.launcher.ui.component.LocalAppLabelScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How a requested widget is measured for the home grid: the same footprint the widget picker gives
 * it, from a cell size the workspace publishes (or an estimate before it has), and the host-id
 * sweep's respect for an id a request still holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class WidgetPinSizingTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    /** Sizes in px; Robolectric's test density is 1, so px are dp here. */
    private fun provider(
        minWidth: Int, minHeight: Int, minResizeWidth: Int = 0, minResizeHeight: Int = 0,
        targetCellWidth: Int = 0, targetCellHeight: Int = 0,
        resizeMode: Int = AppWidgetProviderInfo.RESIZE_NONE,
    ) = AppWidgetProviderInfo().also {
        it.provider = ComponentName("com.clock", "Analog")
        it.minWidth = minWidth
        it.minHeight = minHeight
        it.minResizeWidth = minResizeWidth
        it.minResizeHeight = minResizeHeight
        it.targetCellWidth = targetCellWidth
        it.targetCellHeight = targetCellHeight
        it.resizeMode = resizeMode
    }

    private val bothAxes = AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL

    private val cell = CellSizeDp(70f, 100f)

    /** Robolectric's font scale is 1; the composed home's dock holds [dockApps]. */
    private fun chromeOf(settings: LauncherSettings, pages: Int = 1) =
        workspaceChrome(settings, pages, fontScale = 1f, dockIcons = dockApps.size)

    private fun estimateOf(settings: LauncherSettings, pages: Int = 1, window: HomeWindow? = null) =
        HomeCellSize.estimate(context, settings, chromeOf(settings, pages), window)

    @Test fun theFootprintIsTheSmallestNumberOfCellsThatCoversTheMinimumSize() {
        // 250 dp needs 4 cells of 70; 110 dp needs 2 cells of 100.
        assertThat(requestedWidgetSpans(provider(250, 110), context, cell)).isEqualTo(PinSpans(4, 2, 4, 2))
    }

    @Test fun theFootprintMatchesWhatTheWidgetPickerGives() {
        val info = provider(250, 110, targetCellWidth = 5, targetCellHeight = 1)
        val (sx, sy) = defaultWidgetSpans(info, context, cell.widthDp, cell.heightDp)
        val spans = requestedWidgetSpans(info, context, cell)
        assertThat(spans.spanX to spans.spanY).isEqualTo(sx to sy)
        assertThat(spans.spanX to spans.spanY).isEqualTo(5 to 2)
    }

    @Test fun theMinimumComesFromTheResizeMinimumWhenTheProviderDeclaresOne() {
        val spans = requestedWidgetSpans(
            provider(250, 110, minResizeWidth = 140, minResizeHeight = 50, resizeMode = bothAxes), context, cell,
        )
        assertThat(spans).isEqualTo(PinSpans(4, 2, 2, 1))
    }

    @Test fun aResizeMinimumOnAnAxisTheWidgetCannotBeResizedAlongIsIgnored() {
        // Resizable vertically only: the 50 dp resize width means nothing, the widget is 500 dp wide.
        val info = provider(
            500, 110, minResizeWidth = 50, minResizeHeight = 50, resizeMode = AppWidgetProviderInfo.RESIZE_VERTICAL,
        )
        val spans = requestedWidgetSpans(info, context, CellSizeDp(100f, 100f))
        assertThat(spans.minSpanX to spans.minSpanY).isEqualTo(5 to 1)
    }

    @Test fun aResizeMinimumLargerThanTheMinimumSizeDoesNotRaiseTheMinimum() {
        val spans = requestedWidgetSpans(
            provider(250, 110, minResizeWidth = 400, minResizeHeight = 300, resizeMode = bothAxes), context, cell,
        )
        assertThat(spans.minSpanX to spans.minSpanY).isEqualTo(4 to 2)
    }

    @Test fun theWorkspacePublishesItsCellSize() {
        val drag = HomeDragController()
        val cellSize = HomeCellSize()
        compose.setContent {
            Workspace(
                pageCount = 1, columns = 4, rows = 6, entries = emptyList(), badges = emptyMap(),
                badgeShowCount = false, badgeScale = 1f, showLabels = true, showPageIndicator = true,
                locked = false, homeSignals = emptyFlow(), homePage = 0, dragController = drag,
                widgetDragController = remember { WidgetDragController(drag) },
                onAddWidget = { _, _ -> }, onAppClick = {}, onAppMenu = { _, _, _ -> },
                onMove = { _, _, _, _ -> true }, onMoveFolder = { _, _, _, _ -> true }, onMoveToDock = { _, _ -> },
                onRemoveFromHome = {}, onOpenFolder = {}, onLaunchShortcut = {}, onRemoveShortcut = {},
                onCreateFolder = { _, _ -> }, onAddToFolder = { _, _ -> }, onEmptyAreaMenu = { _, _ -> },
                cellSize = cellSize,
                // Within Robolectric's 320×470 dp window, so nothing is clipped.
                modifier = Modifier.size(300.dp, 450.dp),
            )
        }
        compose.waitForIdle()

        // A single page shows no page dots, so the pager is the whole 300×450 dp.
        val published = cellSize.cellFor(columns = 4, rows = 6)!!
        assertThat(published.widthDp).isWithin(0.01f).of(75f)
        assertThat(published.heightDp).isWithin(0.01f).of(75f)
    }

    /** The grid's counts changed in Settings while the home screen stayed in the background: its
     *  measured area still stands, divided by the counts in force now. */
    @Test fun aCellMeasuredForOtherCountsIsRescaledToTheGridInForce() {
        val cellSize = HomeCellSize().apply { publish(100f, 100f, columns = 4, rows = 6) }

        val cell = cellSize.cellFor(columns = 6, rows = 6)!!

        assertThat(cell.widthDp).isWithin(0.01f).of(400f / 6)
        assertThat(cell.heightDp).isWithin(0.01f).of(100f)
        // A 300 dp wide widget then needs five of those cells, not the three of the old grid.
        assertThat(requestedWidgetSpans(provider(300, 50), context, cell).spanX).isEqualTo(5)
    }

    private fun screen(widthDp: Int, heightDp: Int) = Configuration().apply {
        screenWidthDp = widthDp
        screenHeightDp = heightDp
        smallestScreenWidthDp = minOf(widthDp, heightDp)
        orientation = if (widthDp > heightDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
    }

    /** A large screen turned while the home screen was in the background: from Android 16 on the
     *  launcher turns with it, so its upright cell is not the sideways home's and is not used (the
     *  estimate of the sideways home stands in). */
    @Config(sdk = [36])
    @Test fun fromAndroid16ACellMeasuredUprightOnALargeScreenIsNotUsedForTheSidewaysHome() {
        val cellSize = HomeCellSize().apply { publish(200f, 200f, columns = 4, rows = 6, configuration = screen(800, 1280)) }

        assertThat(cellSize.cellFor(4, 6, screen(1280, 800))).isNull()
        assertThat(cellSize.cellFor(4, 6, screen(800, 1280))).isNotNull()
    }

    /** Before Android 16 the system keeps even a large screen's launcher upright, as on a phone: its
     *  upright cell serves a request from an app held sideways. */
    @Test fun beforeAndroid16ALargeScreensUprightCellServesARequestFromASidewaysApp() {
        val cellSize = HomeCellSize().apply { publish(200f, 200f, columns = 4, rows = 6, configuration = screen(800, 1280)) }

        assertThat(cellSize.cellFor(4, 6, screen(1280, 800))).isNotNull()
    }

    /** On a phone the home screen is always upright, so its cell serves an app held sideways too. */
    @Test fun onAPhoneTheUprightCellServesARequestFromASidewaysApp() {
        val cellSize = HomeCellSize().apply { publish(100f, 100f, columns = 4, rows = 6, configuration = screen(400, 800)) }

        assertThat(cellSize.cellFor(4, 6, screen(800, 400))).isNotNull()
    }

    private fun chrome(dock: Boolean, pages: Int = 2) =
        chromeOf(LauncherSettings(dockEnabled = dock), pages)

    /** The dock turned on in Settings while the home screen stayed in the background: the grid it
     *  measured had the dock's height too, so that cell is not used (the estimate, which counts the
     *  dock, stands in). */
    @Test fun aCellMeasuredWithOtherChromeAroundTheGridIsNotUsed() {
        val cellSize = HomeCellSize().apply { publish(100f, 100f, columns = 4, rows = 6, chrome = chrome(dock = false)) }

        assertThat(cellSize.cellFor(4, 6, chrome = chrome(dock = true))).isNull()
        assertThat(cellSize.cellFor(4, 6, chrome = chrome(dock = false))).isNotNull()
    }

    /** The icon size changed in Settings while the home screen stayed in the background: the dock's
     *  icons, and with them the dock, are taller or shorter now than when the grid was measured, so
     *  that cell is not used. */
    @Test fun aCellMeasuredAtAnotherIconScaleIsNotUsed() {
        val cellSize = HomeCellSize().apply { publish(100f, 100f, columns = 4, rows = 6, chrome = chromeOf(LauncherSettings(iconScale = 1f))) }

        assertThat(cellSize.cellFor(4, 6, chrome = chromeOf(LauncherSettings(iconScale = 1.3f)))).isNull()
        assertThat(cellSize.cellFor(4, 6, chrome = chromeOf(LauncherSettings(iconScale = 1f)))).isNotNull()
    }

    /** Without labels the dock row's least height (72 dp) holds the icons at their largest (52 + 2 × 10)
     *  however few there are — at the default scale. Enlarged, fewer (so larger) icons make the dock
     *  taller again, and the count is part of the chrome. */
    @Test fun withoutLabelsTheDockIconCountMattersOnlyForEnlargedIcons() {
        val noLabels = LauncherSettings(showDockLabels = false)
        assertThat(workspaceChrome(noLabels, 1, 1f, dockIcons = 2)).isEqualTo(workspaceChrome(noLabels, 1, 1f, dockIcons = 5))

        val enlarged = noLabels.copy(iconScale = 1.3f)
        assertThat(workspaceChrome(enlarged, 1, 1f, dockIcons = 2)).isNotEqualTo(workspaceChrome(enlarged, 1, 1f, dockIcons = 5))
    }

    /** The dock's icons follow the user's icon-size setting (until their slots cap them), and the dock
     *  grows with them: the estimate counts them at their largest at that scale, so its cells come out
     *  no taller than the workspace's. At 130 % a 52 dp icon is 67.6 dp, and the unlabelled row grows
     *  from its least 72 dp to 20 + 67.6 — the grid loses that over its rows. No dock, no difference. */
    @Test fun theEstimateCountsTheDocksIconsAtTheUsersIconScale() {
        val plain = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6, showDockLabels = false))
        val large = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6, showDockLabels = false, iconScale = 1.3f))
        assertThat(large.heightDp).isWithin(0.01f).of(plain.heightDp - (20f + 52f * 1.3f - 72f) / 6)

        val noDockPlain = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6, dockEnabled = false))
        val noDockLarge = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6, dockEnabled = false, iconScale = 1.3f))
        assertThat(noDockLarge.heightDp).isWithin(0.01f).of(noDockPlain.heightDp)
    }

    /** The page dots' row (8 dp above and below the ringed home dot's 9) is the only difference a
     *  second page makes: the measured grid stands, less that row — or plus it, the other way. */
    @Test fun aCellMeasuredWithoutThePageDotsServesTheGridWithThemLessTheirRow() {
        val withoutDots = HomeCellSize().apply { publish(75f, 75f, columns = 4, rows = 6, chrome = chrome(dock = true, pages = 1)) }
        val withDots = HomeCellSize().apply { publish(75f, 75f, columns = 4, rows = 6, chrome = chrome(dock = true, pages = 2)) }

        val shorter = withoutDots.cellFor(4, 6, chrome = chrome(dock = true, pages = 2))!!
        val taller = withDots.cellFor(4, 6, chrome = chrome(dock = true, pages = 1))!!

        assertThat(shorter.widthDp).isWithin(0.01f).of(75f)
        assertThat(shorter.heightDp * 6).isWithin(0.01f).of(450f - 25f)
        assertThat(taller.heightDp * 6).isWithin(0.01f).of(450f + 25f)
    }

    private val dockApps = (1..4).map { AppItem("com.app$it", "Main", Process.myUserHandle(), 0L, "App $it") }

    private val statusViewModel by lazy {
        StatusViewModel(
            BatteryMonitor(context), ConnectivityMonitor(context), SignalMonitor(context), SystemFlagsMonitor(context),
            NotificationBadgeRepository(),
        )
    }

    /** A real status bar, workspace and dock in the column HomeScreen puts them in (Robolectric's
     *  window has no bars and no cutout). */
    @Composable
    private fun HomeColumn(settings: LauncherSettings, pageCount: Int, cellSize: HomeCellSize) {
        val drag = remember { HomeDragController() }
        CompositionLocalProvider(LocalAppLabelScale provides settings.appLabelTextScale) {
            Column(Modifier.fillMaxSize()) {
                if (settings.showStatusBar) {
                    StatusBar(
                        alignToCutout = settings.hideSystemStatusBar,
                        modifier = Modifier.fillMaxWidth()
                            .then(if (settings.hideSystemStatusBar) Modifier.heightIn(min = STATUS_ZONE_MIN) else Modifier),
                        viewModel = statusViewModel,
                    )
                }
                Workspace(
                    pageCount = pageCount, columns = settings.homeColumns, rows = settings.homeRows,
                    entries = emptyList(), badges = emptyMap(), badgeShowCount = false, badgeScale = 1f,
                    showLabels = true, showPageIndicator = settings.showPageIndicator,
                    homePageMarker = settings.homePageMarker, locked = false, homeSignals = emptyFlow(),
                    homePage = 0, dragController = drag, widgetDragController = remember { WidgetDragController(drag) },
                    onAddWidget = { _, _ -> }, onAppClick = {}, onAppMenu = { _, _, _ -> },
                    onMove = { _, _, _, _ -> true }, onMoveFolder = { _, _, _, _ -> true }, onMoveToDock = { _, _ -> },
                    onRemoveFromHome = {}, onOpenFolder = {}, onLaunchShortcut = {}, onRemoveShortcut = {},
                    onCreateFolder = { _, _ -> }, onAddToFolder = { _, _ -> }, onEmptyAreaMenu = { _, _ -> },
                    cellSize = cellSize, chrome = workspaceChrome(settings, pageCount, LocalDensity.current.fontScale, dockApps.size),
                    modifier = Modifier.weight(1f).fillMaxWidth().homeWorkspacePadding(settings.showStatusBar, dockShown = settings.dockEnabled),
                )
                if (settings.dockEnabled) {
                    Dock(
                        apps = dockApps, badges = emptyMap(), badgeShowCount = false, badgeScale = 1f,
                        showLabels = settings.showDockLabels, backgroundAlpha = 0.35f, locked = false,
                        dragController = drag, onAppClick = {}, onReorder = {}, onMoveToHome = { _, _, _, _ -> },
                        modifier = Modifier.fillMaxWidth().homeDockPadding(),
                    )
                }
            }
        }
    }

    /** Lays out [HomeColumn] for each of [configs] (settings, page count) in turn and hands over the cell it measured. */
    private fun measureHome(configs: List<Pair<LauncherSettings, Int>>, check: (LauncherSettings, Int, CellSizeDp) -> Unit) {
        var current by mutableStateOf(configs.first())
        val cellSize = HomeCellSize()
        compose.setContent { LauncherTheme { HomeColumn(current.first, current.second, cellSize) } }
        for (config in configs) {
            current = config
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            val (settings, pages) = config
            check(settings, pages, cellSize.cellFor(settings.homeColumns, settings.homeRows, chrome = chromeOf(settings, pages))!!)
        }
    }

    /** What a second page takes from the grid is the row the workspace really gives its dots. */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theDotsRowTheSizingAllowsForIsTheOneTheWorkspaceShows() {
        val ring = LauncherSettings()
        val house = LauncherSettings(homePageMarker = LauncherSettings.MARKER_HOUSE)
        val onePage = HashMap<LauncherSettings, CellSizeDp>()
        measureHome(listOf(ring to 1, ring to 2, house to 1, house to 2)) { settings, pages, cell ->
            if (pages == 1) {
                onePage[settings] = cell
                return@measureHome
            }
            val measuredOnOnePage = HomeCellSize().apply {
                onePage.getValue(settings).let { publish(it.widthDp, it.heightDp, 4, 6, chrome = chromeOf(settings, 1)) }
            }
            val derived = measuredOnOnePage.cellFor(4, 6, chrome = chromeOf(settings, 2))!!
            assertWithMessage(settings.homePageMarker).that(derived.heightDp).isWithin(0.01f).of(cell.heightDp)
        }
    }

    /** Before the workspace has laid out (a cold start for the request) the estimate stands in, for the
     *  minimum too: its cells must never be taller than the home screen's, or a widget gets fewer rows
     *  than it needs — and not much shorter either. Native graphics: the legacy mode's text metrics
     *  make the dock's one-line labels 35 dp tall. */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theEstimateGivesNoTallerCellsThanTheHomeScreenAndAboutTheSame() {
        measureHome(
            listOf(
                LauncherSettings() to 1,
                LauncherSettings() to 2,
                LauncherSettings(showDockLabels = true) to 2,
                LauncherSettings(showDockLabels = true, appLabelTextScale = 1.6f) to 1,
                LauncherSettings(dockEnabled = false) to 2,
                LauncherSettings(homeRows = 8, homePageMarker = LauncherSettings.MARKER_HOUSE) to 2,
                LauncherSettings(showStatusBar = true) to 1,
                LauncherSettings(showStatusBar = true, hideSystemStatusBar = true, showDockLabels = true) to 2,
            ),
        ) { settings, pages, real ->
            val estimate = estimateOf(settings, pages)
            val what = "$pages page(s), dock ${settings.dockEnabled}, labels ${settings.showDockLabels} × " +
                "${settings.appLabelTextScale}, themed bar ${settings.showStatusBar}"
            // The themed bar's second line (5G, the Wi-Fi band) only shows with a connection to name.
            val secondLine = if (settings.showStatusBar) 9f / settings.homeRows else 0f
            assertWithMessage(what).that(estimate.widthDp).isWithin(0.01f).of(real.widthDp)
            assertWithMessage(what).that(estimate.heightDp).isAtMost(real.heightDp + 0.01f)
            assertWithMessage(what).that(estimate.heightDp).isAtLeast(real.heightDp - 0.5f - secondLine)
        }
    }

    /** The home screen hides the system bar on its own setting, themed bar or not, and a shown one pads
     *  the workspace: a cell measured with it hidden is not the grid's once it shows. */
    @Test fun theSystemBarIsPartOfTheChromeWithoutTheThemedBarToo() {
        val hidden = chromeOf(LauncherSettings(showStatusBar = false, hideSystemStatusBar = true))
        val shown = chromeOf(LauncherSettings(showStatusBar = false, hideSystemStatusBar = false))
        val cellSize = HomeCellSize().apply { publish(100f, 100f, columns = 4, rows = 6, chrome = hidden) }

        assertThat(cellSize.cellFor(4, 6, chrome = shown)).isNull()
        assertThat(cellSize.cellFor(4, 6, chrome = hidden)).isNotNull()
    }

    /** A foldable opened or closed meanwhile: another screen altogether. */
    @Test fun aCellMeasuredOnAnotherScreenSizeIsNotUsed() {
        val cellSize = HomeCellSize().apply { publish(100f, 100f, columns = 4, rows = 6, configuration = screen(400, 800)) }

        assertThat(cellSize.cellFor(4, 6, screen(700, 840))).isNull()
    }

    @Test fun theEstimateSpansTheDisplayWidthAndLeavesRoomBelowTheGrid() {
        val screenWidthDp = context.resources.configuration.screenWidthDp.toFloat()
        val screenHeightDp = context.resources.configuration.screenHeightDp.toFloat()
        val four = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6))
        val five = estimateOf(LauncherSettings(homeColumns = 5, homeRows = 8))

        assertThat(four.widthDp).isWithin(0.01f).of(screenWidthDp / 4)
        assertThat(five.widthDp).isWithin(0.01f).of(screenWidthDp / 5)
        assertThat(four.heightDp).isGreaterThan(0f)
        assertThat(four.heightDp * 6).isLessThan(screenHeightDp)
        assertThat(five.heightDp).isLessThan(four.heightDp)
        // Without the dock the grid is taller.
        val noDock = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6, dockEnabled = false))
        assertThat(noDock.heightDp).isGreaterThan(four.heightDp)
    }

    /** A landscape app's request starting the launcher's process on a phone: the home screen there is
     *  upright (the launcher is locked to portrait on phones), so its cells are estimated upright. */
    @Config(qualifiers = "+w800dp-h400dp-land")
    @Test fun aPhoneOnItsSideIsEstimatedAsTheUprightHomeScreen() {
        val estimate = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6))
        assertThat(estimate.widthDp).isWithin(0.01f).of(100f)
        assertThat(estimate.heightDp * 6).isGreaterThan(400f)
    }

    /** From Android 16 a large screen's launcher turns with the device, so the current orientation stands. */
    @Config(sdk = [36], qualifiers = "+w1280dp-h800dp-land")
    @Test fun fromAndroid16ALargeScreenIsEstimatedInItsCurrentOrientation() {
        val estimate = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6))
        assertThat(estimate.widthDp).isWithin(0.01f).of(320f)
    }

    /** Before Android 16 the system honours the launcher's portrait lock on a large screen too: a request
     *  from a sideways app is measured on the upright home, 800 dp wide (a 250 dp widget needs two of
     *  its cells, not one of the sideways grid's 320 dp). */
    @Config(qualifiers = "+w1280dp-h800dp-land")
    @Test fun beforeAndroid16ALargeScreenIsEstimatedUpright() {
        val estimate = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6))
        assertThat(estimate.widthDp).isWithin(0.01f).of(200f)
    }

    /** What the grid loses to the window's bars, over its six rows. */
    private fun lostToBars(settings: LauncherSettings, window: HomeWindow) =
        (estimateOf(settings, window = HomeWindow(window.widthDp, window.heightDp)).heightDp -
            estimateOf(settings, window = window).heightDp) * settings.homeRows

    /** The window's bars come off the grid where HomeScreen's column takes them: the system status bar
     *  while it shows, the navigation bar under the dock (without a dock the grid reaches under it). */
    @Test fun theWindowsBarsComeOffTheGridWhereTheHomeScreenTakesThem() {
        val bars = HomeWindow(320f, 470f, statusBarDp = 24f, navigationBarDp = 48f)

        assertThat(lostToBars(LauncherSettings(), bars)).isWithin(0.01f).of(24f + 48f)
        assertThat(lostToBars(LauncherSettings(hideSystemStatusBar = true), bars)).isWithin(0.01f).of(48f)
        assertThat(lostToBars(LauncherSettings(dockEnabled = false), bars)).isWithin(0.01f).of(24f)
    }

    /** The themed bar counts at its tallest: under a shown system bar, that bar and its own two lines
     *  and fade; in the hidden bar's place, at least the cutout's band, its glyphs dropped level with
     *  the camera's centre. */
    @Test fun theThemedBarCountsAtItsTallestBesideTheSystemBarOrTheCamera() {
        val bar = HomeWindow(320f, 470f, statusBarDp = 24f)
        val camera = HomeWindow(320f, 470f, statusBarDp = 40f, cutoutDp = 40f, cutoutCenterDp = 20f)

        assertThat(lostToBars(LauncherSettings(showStatusBar = true), bar)).isWithin(0.01f).of(24f)
        assertThat((estimateOf(LauncherSettings()).heightDp - estimateOf(LauncherSettings(showStatusBar = true)).heightDp) * 6)
            .isWithin(0.01f).of(STATUS_BAR_TALLEST.value)
        // No camera: the band's 24 dp, the glyphs' 35 below it are more.
        assertThat((estimateOf(LauncherSettings(hideSystemStatusBar = true)).heightDp -
            estimateOf(LauncherSettings(showStatusBar = true, hideSystemStatusBar = true)).heightDp) * 6)
            .isWithin(0.01f).of(STATUS_BAR_TALLEST.value)
        // The camera's centre 20 dp down: the glyph line's centre goes there, 11 dp from the top, and the bar ends 35 below.
        val themed = estimateOf(LauncherSettings(showStatusBar = true, hideSystemStatusBar = true), window = camera)
        val none = estimateOf(LauncherSettings(hideSystemStatusBar = true), window = camera)
        assertThat((none.heightDp - themed.heightDp) * 6).isWithin(0.01f).of(20f - 9f + STATUS_BAR_TALLEST.value)
    }

    /** A phone app held sideways: the upright home screen's window is the same display turned, the
     *  bars and the cutout counted at their largest since they sit on other edges now. */
    @Config(qualifiers = "+w800dp-h400dp-land")
    @Test fun aPhoneOnItsSideIsEstimatedInItsWindowTurnedUpright() {
        val sideways = HomeWindow(800f, 400f, statusBarDp = 24f, navigationBarDp = 48f, cutoutDp = 30f, cutoutCenterDp = null)

        val estimate = estimateOf(LauncherSettings(homeColumns = 4, homeRows = 6), window = sideways)

        assertThat(estimate.widthDp).isWithin(0.01f).of(100f)
        // 800 high: the status bar at least the cutout's 30, the workspace's 24, the dock's 28 + 72 over the 48 bar.
        assertThat(estimate.heightDp * 6).isWithin(0.01f).of(800f - 30f - 24f - (48f + 28f + 72f))
    }

    @Test fun theConfirmationsWindowIsReadAsTheHomeScreensWindow() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val window = homeWindowOf(activity)!!

        assertThat(window.widthDp).isWithin(0.5f).of(320f)
        assertThat(window.heightDp).isWithin(0.5f).of(470f)
    }
}
