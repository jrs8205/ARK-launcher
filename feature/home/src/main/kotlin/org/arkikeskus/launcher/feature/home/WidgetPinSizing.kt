package org.arkikeskus.launcher.feature.home

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets
import org.arkikeskus.launcher.data.PinSpans
import org.arkikeskus.launcher.data.WidgetBinder
import org.arkikeskus.launcher.model.LauncherSettings
import androidx.compose.ui.unit.dp
import org.arkikeskus.launcher.ui.component.iconSizeForCell
import org.arkikeskus.launcher.ui.component.labelBlockHeight
import org.arkikeskus.launcher.ui.component.labelFontFactor

/**
 * The launcher's own widget host ([APPWIDGET_HOST_ID]) and the system's widget manager as a
 * [WidgetBinder]. A second host object on the same id, as Launcher3's AddItemActivity makes
 * (Apache-2.0): ids are the host id's, not the object's, so the home screen's host shows what this
 * one allocated. No size options at the bind, as the home screen's own add path: the sizes follow
 * once the widget is laid out there.
 */
internal class SystemWidgetBinder(context: Context) : WidgetBinder {
    private val appContext = context.applicationContext
    private val host by lazy { AppWidgetHost(appContext, APPWIDGET_HOST_ID) }
    private val manager by lazy { AppWidgetManager.getInstance(appContext) }

    override fun allocateId(): Int? = host.allocateAppWidgetId().takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }

    override fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean =
        manager.bindAppWidgetIdIfAllowed(appWidgetId, info.profile, info.provider, null)

    override fun deleteId(appWidgetId: Int) = host.deleteAppWidgetId(appWidgetId)
}

/**
 * What the workspace's height depends on besides the screen and the grid's counts: the themed status
 * bar above it (and whether the system bar is hidden, behind it or not), the dock below it with its
 * labels' height (0 without), the user's icon scale (the dock's icons, and so its row, grow with it)
 * and the number of its icons where that changes the row's height (0 where it doesn't), and the
 * page dots' row inside it (0 without dots). A cell measured with other chrome is not this home
 * screen's — unless only the dots differ, whose row is known.
 */
internal data class WorkspaceChrome(
    val dock: Boolean,
    val dockLabelsDp: Float,
    val dockIcons: Int,
    val iconScale: Float,
    val statusBar: Boolean,
    val systemStatusBarHidden: Boolean,
    val pageDotsDp: Float,
    /** Widgets keep an icon's margin inside their cells (the setting; WidgetInset.kt). */
    val widgetInsets: Boolean = false,
    /** The height of a home icon's label block, which the icon size in a cell depends on. */
    val homeLabelsDp: Float = 0f,
)

/** The chrome of a home screen with [settings], [pageCount] pages (the dots show from two on) and
 *  [dockIcons] apps in its dock, at the system's [fontScale] (the dock's labels follow it). */
internal fun workspaceChrome(settings: LauncherSettings, pageCount: Int, fontScale: Float, dockIcons: Int): WorkspaceChrome {
    val dockLabelsDp = if (settings.dockEnabled) {
        labelBlockHeight(settings.showDockLabels, settings.appLabelTextScale, labelFontFactor(fontScale)).value
    } else {
        0f
    }
    return WorkspaceChrome(
        dock = settings.dockEnabled,
        dockLabelsDp = dockLabelsDp,
        // The fewer the icons the larger they are (up to their nominal size), and a larger icon makes
        // the dock taller over labels, or at any scale above the default — without labels at the
        // default scale the row's least height holds even the largest (52 + 2 × 10 = 72).
        dockIcons = if (dockLabelsDp > 0f || settings.iconScale > 1f) dockIcons else 0,
        iconScale = settings.iconScale,
        statusBar = settings.showStatusBar,
        // Its own setting: with the themed bar off, a shown system bar pads the workspace instead.
        systemStatusBarHidden = settings.hideSystemStatusBar,
        pageDotsDp = if (settings.showPageIndicator && pageCount > 1) pageDotsRowHeight(settings.homePageMarker).value else 0f,
        widgetInsets = settings.widgetEdgesAlignWithIcons,
        homeLabelsDp = labelBlockHeight(
            settings.showHomeLabels, settings.appLabelTextScale, labelFontFactor(fontScale),
            lines = if (settings.twoLineHomeLabels) 2 else 1,
        ).value,
    )
}

/**
 * The window the home screen lays out in, in dp: the display area, the status and navigation bars
 * (their sizes whether or not they show right now) and the camera cutout — its deepest inset on any
 * edge, and the vertical centre of a top one ([topCutoutRect]) when it has one.
 */
internal data class HomeWindow(
    val widthDp: Float,
    val heightDp: Float,
    val statusBarDp: Float = 0f,
    val navigationBarDp: Float = 0f,
    val cutoutDp: Float = 0f,
    val cutoutCenterDp: Float? = null,
) {
    /** The same display held upright, for a phone's home screen (locked upright) seen from an app on
     *  its side: the bars and the cutout sit on other edges there, so the largest of them stand in. */
    fun upright() = HomeWindow(heightDp, widthDp, maxOf(statusBarDp, cutoutDp), navigationBarDp, cutoutDp)
}

/** The window [activity] shows in as a [HomeWindow]: the home screen's own, as it is always full
 *  screen on the same display. Null when its metrics can't be read. */
internal fun homeWindowOf(activity: Activity): HomeWindow? = runCatching {
    val metrics = activity.windowManager.maximumWindowMetrics
    val density = activity.resources.displayMetrics.density
    val insets = metrics.windowInsets
    val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
    val navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
    val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
    HomeWindow(
        widthDp = metrics.bounds.width() / density,
        heightDp = metrics.bounds.height() / density,
        statusBarDp = bars.top / density,
        navigationBarDp = maxOf(navigation.bottom, navigation.left, navigation.right) / density,
        cutoutDp = maxOf(cutout.top, cutout.left, cutout.right, cutout.bottom) / density,
        cutoutCenterDp = topCutoutRect(insets.displayCutout)?.let { it.exactCenterY() / density },
    )
}.getOrNull()

/** The home grid's cell size in dp, with the widget inset the grid applies on each side (WidgetInset.kt; 0 off). */
data class CellSizeDp(val widthDp: Float, val heightDp: Float, val insetXDp: Float = 0f, val insetYDp: Float = 0f) {
    /** This cell with the inset the grid of [chrome] gives widgets: the icon's side margin in it (none without the setting). */
    internal fun withInsets(chrome: WorkspaceChrome?): CellSizeDp {
        if (chrome == null || !chrome.widgetInsets) return copy(insetXDp = 0f, insetYDp = 0f)
        val icon = iconSizeForCell(widthDp.dp, (HOME_ICON_NOMINAL_DP * chrome.iconScale).dp, heightDp.dp, chrome.homeLabelsDp.dp).value
        val (x, y) = widgetInsetsDp(widthDp, heightDp, icon)
        return copy(insetXDp = x, insetYDp = y)
    }
}

/** The nominal home icon size the grid scales by the icon-size setting (Workspace's 52 dp). */
internal const val HOME_ICON_NOMINAL_DP = 52f

/**
 * The home grid's cell size as [Workspace] last laid it out, for the rest of the process: the
 * confirmation of another app's widget request is a separate activity that must measure the
 * widget exactly as the widget picker would, and the picker reads the size from the workspace.
 * Null until the workspace has laid out in this process (the request started it); [estimate]
 * stands in then. One per process ([shared]); tests take their own.
 */
class HomeCellSize {
    /** The grid as last laid out: its cell, the counts the cell was measured for, the screen and the
     *  chrome around the grid. */
    private class Measured(
        val cell: CellSizeDp, val columns: Int, val rows: Int, val screen: Screen?, val chrome: WorkspaceChrome?,
    )

    /** What a measured grid area depends on besides the counts: the display's size, and the home
     *  screen's orientation — always upright where the launcher's portrait lock holds ([turnsWithTheDevice]). */
    private data class Screen(val smallestWidthDp: Int, val landscape: Boolean)

    @Volatile private var measured: Measured? = null

    /** The workspace's cell, for a grid of [columns]×[rows] on the screen of [configuration]. */
    internal fun publish(
        widthDp: Float, heightDp: Float, columns: Int, rows: Int,
        configuration: Configuration? = null, chrome: WorkspaceChrome? = null,
        insetXDp: Float = 0f, insetYDp: Float = 0f,
    ) {
        if (widthDp > 0f && heightDp > 0f && columns > 0 && rows > 0) {
            measured = Measured(CellSizeDp(widthDp, heightDp, insetXDp, insetYDp), columns, rows, configuration?.let(::screenOf), chrome)
        }
    }

    /**
     * The cell of a [columns]×[rows] grid on the screen of [configuration]. The counts can change in
     * Settings while the home screen stays in the background and measures nothing; the grid's area
     * doesn't depend on them, so a cell measured for other counts is rescaled. The area does depend on
     * the screen and on the [chrome] around the grid: a large screen turned, a foldable opened or the
     * dock turned on since the measurement make it unknown (null, as before the workspace has laid out
     * at all). The page dots alone coming or going (with a page added or removed) don't: their row is
     * known, and the grid gets or gives it back.
     */
    internal fun cellFor(
        columns: Int, rows: Int, configuration: Configuration? = null, chrome: WorkspaceChrome? = null,
    ): CellSizeDp? {
        val m = measured ?: return null
        val screen = configuration?.let(::screenOf)
        if (screen != null && m.screen != null && screen != m.screen) return null
        var dotsGivenBackDp = 0f
        if (chrome != null && m.chrome != null && chrome != m.chrome) {
            if (chrome.copy(pageDotsDp = m.chrome.pageDotsDp) != m.chrome) return null
            dotsGivenBackDp = m.chrome.pageDotsDp - chrome.pageDotsDp
        }
        if (m.columns == columns && m.rows == rows && dotsGivenBackDp == 0f) return m.cell
        // A rescaled cell gets its inset from the same rule the workspace applies.
        return CellSizeDp(
            m.cell.widthDp * m.columns / columns.coerceAtLeast(1),
            (m.cell.heightDp * m.rows + dotsGivenBackDp) / rows.coerceAtLeast(1),
        ).withInsets(chrome ?: m.chrome)
    }

    companion object {
        val shared = HomeCellSize()

        private fun screenOf(configuration: Configuration) = Screen(
            smallestWidthDp = configuration.smallestScreenWidthDp,
            landscape = turnsWithTheDevice(configuration) && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )

        /** Whether the platform turns the portrait-locked launcher with the device on the screen of
         *  [configuration]: on a large one from Android 16 on. Before, and on a phone, it stays upright. */
        private fun turnsWithTheDevice(configuration: Configuration): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP

        /**
         * The cell the workspace would give the grid in [settings] with [chrome] around it, before it
         * has laid out for them (a request starting the launcher's process, Settings changed while the
         * home screen was away): laid out as HomeScreen lays it out in [window] — the confirmation's
         * own, the home screen's as well — or, without one, on the configuration's screen with no
         * bars. The grid spans the full width; its height is the window's less the status bar's zone,
         * the workspace's padding, the page dots' row and the dock's zone. Where the home screen's own
         * sizes vary (the themed bar's second line, the dock's icons shrinking with more apps in it)
         * the largest is taken: the cells come out no taller than the workspace will make them, so a
         * widget measured on them gets at least the rows it needs.
         */
        internal fun estimate(context: Context, settings: LauncherSettings, chrome: WorkspaceChrome, window: HomeWindow? = null): CellSizeDp {
            val configuration = context.resources.configuration
            val shown = window ?: HomeWindow(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
            // The requesting app's orientation is not the home screen's: where the launcher's portrait
            // lock holds, a request from an app on its side is measured upright. A large screen from
            // Android 16 on (where the platform turns the launcher too) keeps the orientation it has.
            val home = if (!turnsWithTheDevice(configuration) && shown.widthDp > shown.heightDp) {
                shown.upright()
            } else {
                shown
            }
            val gridHeightDp = home.heightDp - statusZoneDp(chrome, home) - 2 * WORKSPACE_VERTICAL_PADDING.value -
                chrome.pageDotsDp - dockZoneDp(chrome, home, onePixelDp = 1f / context.resources.displayMetrics.density)
            return CellSizeDp(
                home.widthDp / settings.homeColumns.coerceAtLeast(1),
                gridHeightDp.coerceAtLeast(1f) / settings.homeRows.coerceAtLeast(1),
            ).withInsets(chrome)
        }

        /** What HomeScreen's column gives the status bar above the workspace. */
        private fun statusZoneDp(chrome: WorkspaceChrome, home: HomeWindow): Float = when {
            // No themed bar: the workspace keeps clear of the system's, unless that is hidden.
            !chrome.statusBar -> if (chrome.systemStatusBarHidden) 0f else home.statusBarDp
            // The themed bar under the system's: that bar's height, then the glyphs and the fade.
            !chrome.systemStatusBarHidden -> home.statusBarDp + STATUS_BAR_TALLEST.value
            // In the system bar's place: at least the cutout's band, the glyphs dropped level with the
            // camera's centre (at most the band's bottom when where it falls isn't known).
            else -> {
                val drop = if (home.cutoutDp <= 0f) {
                    0f
                } else {
                    ((home.cutoutCenterDp ?: home.cutoutDp) - STATUS_MAIN_LINE.value / 2).coerceAtLeast(0f)
                }
                maxOf(home.cutoutDp, STATUS_ZONE_MIN.value, drop + STATUS_BAR_TALLEST.value)
            }
        }

        /** What HomeScreen's column gives the dock below the workspace: its row (the icons at their
         *  largest — their nominal size at the user's icon scale, before the slots cap them — and a
         *  label line rounded up to whole pixels) with its padding, above the navigation bar. Without
         *  the dock the workspace keeps clear of the navigation bar itself. */
        private fun dockZoneDp(chrome: WorkspaceChrome, home: HomeWindow, onePixelDp: Float): Float {
            if (!chrome.dock) return home.navigationBarDp
            val labels = if (chrome.dockLabelsDp > 0f) chrome.dockLabelsDp + onePixelDp else 0f
            val icon = DOCK_ICON_SIZE.value * chrome.iconScale
            val row = maxOf(DOCK_ROW_MIN_HEIGHT.value, 2 * DOCK_ROW_VERTICAL_PADDING.value + icon + labels)
            return home.navigationBarDp + 2 * DOCK_OUTER_PADDING.value + row
        }

        /** From this smallest width on, the platform turns even a portrait-locked launcher (Android 16). */
        private const val LARGE_SCREEN_DP = 600
    }
}

/**
 * The footprint a requested widget gets, in cells of a grid with cells of [cell]: the picker's
 * default ([defaultWidgetSpans]) and the smallest the provider accepts — its resize minimum on an axis
 * it may be resized along, its minimum size on one it may not (the platform ignores a resize minimum
 * there). Unclamped: the decision clamps against the grid.
 */
internal fun requestedWidgetSpans(info: AppWidgetProviderInfo, context: Context, cell: CellSizeDp): PinSpans {
    val (spanX, spanY) = defaultWidgetSpans(info, context, cell.widthDp, cell.heightDp, cell.insetXDp, cell.insetYDp)
    val density = context.resources.displayMetrics.density
    val resizesX = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    val resizesY = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    val minWidth = if (resizesX && info.minResizeWidth > 0) minOf(info.minResizeWidth, info.minWidth) else info.minWidth
    val minHeight = if (resizesY && info.minResizeHeight > 0) minOf(info.minResizeHeight, info.minHeight) else info.minHeight
    return PinSpans(
        spanX, spanY,
        minimumWidgetCells(minWidth / density, cell.widthDp, cell.insetXDp),
        minimumWidgetCells(minHeight / density, cell.heightDp, cell.insetYDp),
    )
}
