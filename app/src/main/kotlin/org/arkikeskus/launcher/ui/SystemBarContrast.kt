package org.arkikeskus.launcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the system status bar draws dark glyphs. Once the drawer covers the bar its surface decides;
 * then a full-screen surface over home ([coverIsLight], see [SystemBarCovers]) when one is shown; on
 * home, ARK's own status bar backs the system bar with a dark scrim when that is strong enough
 * ([ownBarBacksDark]: light glyphs), and otherwise the wallpaper's own hint decides — as in Pixel
 * Launcher, instead of the phone's light/dark mode, which drew black glyphs on dark wallpapers in light
 * mode (issue #11).
 */
internal fun statusBarDarkIcons(
    drawerCoversBar: Boolean,
    drawerSurfaceIsLight: Boolean,
    coverIsLight: Boolean?,
    ownBarBacksDark: Boolean,
    wallpaperSupportsDarkText: Boolean,
): Boolean = when {
    drawerCoversBar -> drawerSurfaceIsLight
    coverIsLight != null -> coverIsLight
    ownBarBacksDark -> false
    else -> wallpaperSupportsDarkText
}

/**
 * ARK's own bar backs the system status bar with a scrim dark enough for light glyphs: its opacity is
 * the user's, and a scrim under [OWN_BAR_DARK_BACKING] leaves a light wallpaper light enough that dark
 * glyphs read better, so the wallpaper's hint decides then.
 */
internal fun ownBarBacksDark(ownBarShown: Boolean, scrimOpacity: Float): Boolean =
    ownBarShown && scrimOpacity >= OWN_BAR_DARK_BACKING

/** Whether the navigation bar (gesture handle / buttons) draws dark glyphs: the drawer's surface once
 *  the drawer covers it, a full-screen surface over home while one is shown, the wallpaper's hint
 *  otherwise. */
internal fun navigationBarDarkIcons(
    drawerCoversBar: Boolean,
    drawerSurfaceIsLight: Boolean,
    coverIsLight: Boolean?,
    wallpaperSupportsDarkText: Boolean,
): Boolean = when {
    drawerCoversBar -> drawerSurfaceIsLight
    coverIsLight != null -> coverIsLight
    else -> wallpaperSupportsDarkText
}

/** The drawer's top edge (it slides up from the bottom) is past the middle of the status bar. */
internal fun drawerCoversStatusBar(progress: Float, heightPx: Float, barPx: Float): Boolean =
    (1f - progress) * heightPx < barPx / 2f

/** The drawer's top edge is past the middle of the navigation bar. */
internal fun drawerCoversNavigationBar(progress: Float, heightPx: Float, barPx: Float): Boolean =
    progress * heightPx > barPx / 2f

/** Strongest darkness of the gradient behind light system-bar glyphs on home (at the very top);
 *  Launcher3's own top scrim peaks a little lighter (24 %) over 70 dp. */
internal const val HOME_TOP_SCRIM_ALPHA = 0.3f

/** The weakest own-bar scrim that counts as dark backing. Black at this opacity over a white wallpaper
 *  leaves about the grey where light and dark glyphs read equally well (relative luminance ≈ 0.18); the
 *  default opacity is 0.6. */
internal const val OWN_BAR_DARK_BACKING = 0.5f

private val HOME_TOP_SCRIM_MIN_HEIGHT = 70.dp

/**
 * Whether home gets a soft dark gradient behind the system status bar. The wallpaper hint describes the
 * whole image, so a wallpaper that is mostly dark but light at the top gets light glyphs on a light
 * strip; like Launcher3's top scrim, the gradient keeps them readable on any wallpaper. Not with ARK's
 * own bar (it has its own scrim, at the opacity the user picked, and the gradient would dim ARK's own
 * glyphs), nor with the system bar hidden (no glyphs to back), nor while a full-screen surface is
 * [covered] over home (it is drawn above home, the gradient would sit on top of it).
 */
internal fun homeTopScrimShown(
    ownBarShown: Boolean,
    systemBarHidden: Boolean,
    wallpaperSupportsDarkText: Boolean,
    covered: Boolean,
): Boolean = !ownBarShown && !systemBarHidden && !wallpaperSupportsDarkText && !covered

/** The wallpaper says dark text reads well on it. The hint constant is inlined at compile time; only
 *  Android 12+ hands over real hints (see [wallpaperSupportsDarkText]). */
@SuppressLint("InlinedApi")
internal fun supportsDarkText(colorHints: Int): Boolean =
    colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0

/** [supportsDarkText] for [colors]; Android 11 does not expose the hints, so its primary colour decides there. */
private fun wallpaperSupportsDarkText(colors: WallpaperColors?): Boolean = when {
    colors == null -> false
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> supportsDarkText(colors.colorHints)
    else -> colors.primaryColor.luminance() > 0.5f
}

/**
 * The system bars' glyph colours as snapshot state, from inputs read as they are now. Every input is a
 * provider: the bar heights only arrive after the first frame, and a decision that kept the first
 * frame's values never saw the drawer covering the status bar.
 */
internal class SystemBarGlyphs(
    progress: () -> Float,
    heightPx: () -> Float,
    statusBarPx: () -> Float,
    navBarPx: () -> Float,
    surfaceIsLight: () -> Boolean,
    coverIsLight: () -> Boolean?,
    ownBarBacksDark: () -> Boolean,
    wallpaperSupportsDarkText: () -> Boolean,
) {
    /** The status bar draws dark glyphs ([statusBarDarkIcons]). */
    val darkStatus: State<Boolean> = derivedStateOf {
        statusBarDarkIcons(
            drawerCoversBar = drawerCoversStatusBar(progress(), heightPx(), statusBarPx()),
            drawerSurfaceIsLight = surfaceIsLight(),
            coverIsLight = coverIsLight(),
            ownBarBacksDark = ownBarBacksDark(),
            wallpaperSupportsDarkText = wallpaperSupportsDarkText(),
        )
    }

    /** The navigation bar draws dark glyphs ([navigationBarDarkIcons]). */
    val darkNav: State<Boolean> = derivedStateOf {
        navigationBarDarkIcons(
            drawerCoversBar = drawerCoversNavigationBar(progress(), heightPx(), navBarPx()),
            drawerSurfaceIsLight = surfaceIsLight(),
            coverIsLight = coverIsLight(),
            wallpaperSupportsDarkText = wallpaperSupportsDarkText(),
        )
    }
}

/**
 * Keeps the system bars' glyphs readable over whatever is behind them (see [statusBarDarkIcons]).
 * [progress] is the drawer's openness (0 closed, 1 open) and [heightPx] the shell's height; all inputs
 * are read in snapshots, so the window is only touched when a glyph colour actually flips.
 */
@Composable
internal fun SystemBarContrast(
    progress: () -> Float,
    heightPx: () -> Float,
    covers: SystemBarCovers,
    ownBarBacksDark: Boolean,
    wallpaperDarkText: State<Boolean>,
) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val density = LocalDensity.current
    val statusBarPx = rememberUpdatedState(WindowInsets.statusBars.getTop(density).toFloat())
    val navBarPx = rememberUpdatedState(WindowInsets.navigationBars.getBottom(density).toFloat())
    val surfaceIsLight = rememberUpdatedState(MaterialTheme.colorScheme.surface.luminance() > 0.5f)
    val ownBar = rememberUpdatedState(ownBarBacksDark)
    val currentProgress = rememberUpdatedState(progress)
    val currentHeight = rememberUpdatedState(heightPx)
    val glyphs = remember {
        SystemBarGlyphs(
            progress = { currentProgress.value() },
            heightPx = { currentHeight.value() },
            statusBarPx = { statusBarPx.value },
            navBarPx = { navBarPx.value },
            surfaceIsLight = { surfaceIsLight.value },
            coverIsLight = { covers.coverIsLight },
            ownBarBacksDark = { ownBar.value },
            wallpaperSupportsDarkText = { wallpaperDarkText.value },
        )
    }
    LaunchedEffect(window, view) {
        snapshotFlow { glyphs.darkStatus.value to glyphs.darkNav.value }.collect { (status, nav) ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = status
                isAppearanceLightNavigationBars = nav
            }
        }
    }
}

/**
 * The top scrim's strength: full until the drawer covers the status bar ([drawerCoversStatusBar]) —
 * the moment the glyphs switch to the drawer's colour — and none after. The rising drawer is drawn
 * over the scrim, so it needs no fade of its own.
 */
internal fun homeTopScrimAlpha(progress: Float, heightPx: Float, barPx: Float): Float =
    if (drawerCoversStatusBar(progress, heightPx, barPx)) 0f else HOME_TOP_SCRIM_ALPHA

/**
 * The soft dark gradient behind the system status bar on home ([homeTopScrimShown]): twice the bar's
 * height, gone once the drawer covers the bar ([homeTopScrimAlpha]). Drawn only, so every touch passes
 * through.
 */
@Composable
internal fun HomeTopScrim(shown: Boolean, progress: () -> Float, heightPx: () -> Float) {
    if (!shown) return
    val density = LocalDensity.current
    val barPx = WindowInsets.statusBars.getTop(density).toFloat()
    val barHeight = with(density) { barPx.toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(maxOf(barHeight * 2, HOME_TOP_SCRIM_MIN_HEIGHT))
            .drawBehind {
                val alpha = homeTopScrimAlpha(progress(), heightPx(), barPx)
                if (alpha > 0f) {
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = alpha), 1f to Color.Transparent))
                }
            },
    )
}

/**
 * The wallpaper's dark-text hint as state: the colours listener's reports and the first read. The first
 * read may take a while, and a report that arrives meanwhile is newer, so the read's answer only lands
 * when no report came during it. Main thread only.
 */
internal class WallpaperDarkText {
    val state = mutableStateOf(false)
    private var reports = 0

    fun onColorsChanged(supportsDarkText: Boolean) {
        reports++
        state.value = supportsDarkText
    }

    suspend fun readInitial(read: suspend () -> Boolean) {
        val before = reports
        val value = read()
        if (reports == before) state.value = value
    }
}

/** The system wallpaper's dark-text hint, kept current as the wallpaper (or its colours) change. */
@Composable
internal fun rememberWallpaperSupportsDarkText(): State<Boolean> {
    val context = LocalContext.current
    val wallpaper = remember { WallpaperDarkText() }
    DisposableEffect(context) {
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) wallpaper.onColorsChanged(wallpaperSupportsDarkText(colors))
        }
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.removeOnColorsChangedListener(listener) }
    }
    LaunchedEffect(context) {
        wallpaper.readInitial {
            // The first read may extract the colours from the image: off the main thread.
            withContext(Dispatchers.IO) {
                runCatching {
                    wallpaperSupportsDarkText(WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
                }.getOrDefault(false)
            }
        }
    }
    return wallpaper.state
}

private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
