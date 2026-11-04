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
import org.arkikeskus.launcher.model.DrawerSurface

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

/**
 * The own bar's scrim as much of it as is on screen: the bar is part of home, which fades out under the
 * rising drawer ([homeContentAlpha], [homeAlpha]), so a scrim at [scrimOpacity] backs the glyphs with
 * only [scrimOpacity] × [homeAlpha] of its darkness -- none once home has faded, whatever the setting.
 */
internal fun ownBarScrimOnScreen(scrimOpacity: Float, homeAlpha: Float): Float =
    scrimOpacity.coerceIn(0f, 1f) * homeAlpha.coerceIn(0f, 1f)

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

/**
 * ARK's own status bar is on screen: [ownBarShown] by the setting, unless home blanks it because the
 * system bar it was to replace is still (or again) on top -- a permanent show that takes layout space
 * ([statusBarPx] > 0 while [systemBarHidden]: freeform/desktop windowing, lock-task) or a heads-up
 * notification's transient reveal ([headsUpActive]). The same rule as HomeScreen's suppressThemedBar;
 * a blanked bar is no backing for the glyphs.
 */
internal fun ownBarVisible(ownBarShown: Boolean, systemBarHidden: Boolean, statusBarPx: Float, headsUpActive: Boolean): Boolean =
    ownBarShown && !(systemBarHidden && (statusBarPx > 0f || headsUpActive))

/**
 * The luminance behind the status bar on home: the wallpaper's dark-text hint taken as white or black,
 * under ARK's own bar's black scrim at [scrimOpacity] while the bar is on screen ([ownBarVisible]). The
 * scrim counts only as strong as it is: the default 60 % over a white wallpaper leaves a mid grey, not
 * black.
 */
internal fun behindStatusBarLuminance(ownBarVisible: Boolean, scrimOpacity: Float, wallpaperSupportsDarkText: Boolean): Float {
    val wallpaper = if (wallpaperSupportsDarkText) 1f else 0f
    return if (ownBarVisible) wallpaper * (1f - scrimOpacity.coerceIn(0f, 1f)) else wallpaper
}

/** The luminance of what the drawer paints ([DrawerSurface.paint], ARGB: the user's colour, or black over the
 *  own picture) when it paints a colour of its own, the theme's [themeSurface] otherwise. */
internal fun drawerSurfaceLuminance(paint: Int?, themeSurface: Color): Float =
    (paint?.let { Color(it) } ?: themeSurface).luminance()

/**
 * Whether the drawer's background, at the user's [opacity] over what it lets through, is light enough
 * for dark glyphs: the surface's [surfaceLuminance] weighted by the opacity, [behindLuminance] by the
 * rest. Opaque, the surface decides as before; see-through, what is behind does -- a light theme over a
 * dark wallpaper drew dark glyphs on dark once the drawer could be transparent (issue #21's opacity
 * setting). The bar's thin glyphs keep the system bars' own stricter rule -- dark only on a clearly light
 * backing (Android's wallpaper hint counts a pixel dark below 0.45) -- not the drawer text's contrast rule
 * (DrawerColors.kt), which turns the body text black from a relative luminance of 0.18 up.
 */
internal fun drawerSurfaceIsLight(surfaceLuminance: Float, opacity: Float, behindLuminance: Float): Boolean =
    surfaceLuminance * opacity + behindLuminance * (1f - opacity) > 0.5f

/**
 * Home as it shows through the rising drawer: the shell dims the whole of home under the drawer by
 * [drawerScrimAlpha] ([homeDim], 32 % when open), so what a see-through drawer lets through is home's
 * [luminance] darkened by that.
 */
internal fun dimmedUnderDrawer(luminance: Float, homeDim: Float): Float = luminance * (1f - homeDim.coerceIn(0f, 1f))

/** What a see-through drawer lets through: the user's own picture behind the drawer
 *  ([pictureLuminance], its mean lightness) when one is set -- it sits inside the drawer and covers
 *  home and the wallpaper alike -- or else [behindHome], home as the shell shows it. */
internal fun behindDrawerLuminance(pictureLuminance: Float?, behindHome: Float): Float =
    pictureLuminance?.coerceIn(0f, 1f) ?: behindHome

/** [drawerSurfaceIsLight] over the status bar: behind a see-through drawer is home's top edge,
 *  [behindStatusBarLuminance] (the own bar's scrim, when on screen, over the wallpaper), under the
 *  shell's dim ([dimmedUnderDrawer]). */
internal fun drawerIsLightOverStatusBar(
    surfaceLuminance: Float,
    opacity: Float,
    ownBarVisible: Boolean,
    scrimOpacity: Float,
    wallpaperSupportsDarkText: Boolean,
    homeDim: Float,
): Boolean = drawerSurfaceIsLight(
    surfaceLuminance,
    opacity,
    dimmedUnderDrawer(behindStatusBarLuminance(ownBarVisible, scrimOpacity, wallpaperSupportsDarkText), homeDim),
)

/** [drawerSurfaceIsLight] over the navigation bar: behind a see-through drawer is the wallpaper under
 *  the shell's dim ([dimmedUnderDrawer]). */
internal fun drawerIsLightOverNavigationBar(surfaceLuminance: Float, opacity: Float, wallpaperSupportsDarkText: Boolean, homeDim: Float): Boolean =
    drawerSurfaceIsLight(surfaceLuminance, opacity, dimmedUnderDrawer(if (wallpaperSupportsDarkText) 1f else 0f, homeDim))

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
 * own bar ON SCREEN ([ownBarVisible]: it has its own scrim, at the opacity the user picked, and the
 * gradient would dim ARK's own glyphs), nor with the system bar really gone (hidden by the setting and
 * neither kept by the system -- [statusBarPx] -- nor revealed for a heads-up: no glyphs to back), nor
 * while a full-screen surface is [covered] over home (it is drawn above home, the gradient would sit on
 * top of it). A blanked own bar under a kept or revealed system bar gets the gradient like no own bar.
 */
internal fun homeTopScrimShown(
    ownBarShown: Boolean,
    systemBarHidden: Boolean,
    statusBarPx: Float,
    headsUpActive: Boolean,
    wallpaperSupportsDarkText: Boolean,
    covered: Boolean,
): Boolean {
    val systemBarGone = systemBarHidden && statusBarPx <= 0f && !headsUpActive
    return !ownBarVisible(ownBarShown, systemBarHidden, statusBarPx, headsUpActive) &&
        !systemBarGone && !wallpaperSupportsDarkText && !covered
}

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
 * frame's values never saw the drawer covering the status bar. The drawer providers get the shell's
 * dim over home at the current [progress] ([drawerScrimAlpha]), and the status-bar providers how much
 * of home -- and so of ARK's own bar -- is still on screen ([homeContentAlpha]), read here inside the
 * snapshot.
 */
internal class SystemBarGlyphs(
    progress: () -> Float,
    heightPx: () -> Float,
    statusBarPx: () -> Float,
    navBarPx: () -> Float,
    /** The drawer, over the status bar, is light enough for dark glyphs ([drawerIsLightOverStatusBar])
     *  with home dimmed by the given amount under it and faded to the given alpha. */
    drawerIsLightOverStatusBar: (homeDim: Float, homeAlpha: Float) -> Boolean,
    /** The same over the navigation bar ([drawerIsLightOverNavigationBar]). */
    drawerIsLightOverNavBar: (homeDim: Float) -> Boolean,
    coverIsLight: () -> Boolean?,
    /** ARK's own bar backs the glyphs with a dark scrim ([ownBarBacksDark]) at home's given alpha. */
    ownBarBacksDark: (homeAlpha: Float) -> Boolean,
    wallpaperSupportsDarkText: () -> Boolean,
) {
    /** The status bar draws dark glyphs ([statusBarDarkIcons]). */
    val darkStatus: State<Boolean> = derivedStateOf {
        val progress = progress()
        val homeAlpha = homeContentAlpha(progress)
        statusBarDarkIcons(
            drawerCoversBar = drawerCoversStatusBar(progress, heightPx(), statusBarPx()),
            drawerSurfaceIsLight = drawerIsLightOverStatusBar(drawerScrimAlpha(progress), homeAlpha),
            coverIsLight = coverIsLight(),
            ownBarBacksDark = ownBarBacksDark(homeAlpha),
            wallpaperSupportsDarkText = wallpaperSupportsDarkText(),
        )
    }

    /** The navigation bar draws dark glyphs ([navigationBarDarkIcons]). */
    val darkNav: State<Boolean> = derivedStateOf {
        val progress = progress()
        navigationBarDarkIcons(
            drawerCoversBar = drawerCoversNavigationBar(progress, heightPx(), navBarPx()),
            drawerSurfaceIsLight = drawerIsLightOverNavBar(drawerScrimAlpha(progress)),
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
    /** ARK's own status bar by the setting; whether it is on screen follows [ownBarVisible]. */
    ownBarShown: Boolean,
    systemBarHidden: Boolean,
    /** The own bar's scrim opacity ([ownBarBacksDark], [behindStatusBarLuminance]). */
    scrimOpacity: Float,
    headsUpActive: Boolean,
    wallpaperDarkText: State<Boolean>,
    /** What the app drawer paints over what is behind it, and how much of it shows ([drawerSurfaceIsLight]). */
    drawerSurface: DrawerSurface = DrawerSurface(tint = null, paint = null, opacity = 1f),
    /** The mean lightness of the user's own picture behind the drawer, or null without one ([behindDrawerLuminance]). */
    drawerPictureLuminance: Float? = null,
) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val density = LocalDensity.current
    val statusBarPx = rememberUpdatedState(WindowInsets.statusBars.getTop(density).toFloat())
    val navBarPx = rememberUpdatedState(WindowInsets.navigationBars.getBottom(density).toFloat())
    val themeSurface = MaterialTheme.colorScheme.surface
    val surfaceLuminance = rememberUpdatedState(drawerSurfaceLuminance(drawerSurface.paint, themeSurface))
    val currentDrawerOpacity = rememberUpdatedState(drawerSurface.opacity)
    val currentPicture = rememberUpdatedState(drawerPictureLuminance)
    val currentOwnBarShown = rememberUpdatedState(ownBarShown)
    val currentSystemBarHidden = rememberUpdatedState(systemBarHidden)
    val currentScrim = rememberUpdatedState(scrimOpacity)
    val currentHeadsUp = rememberUpdatedState(headsUpActive)
    val ownBarOnScreen = {
        ownBarVisible(currentOwnBarShown.value, currentSystemBarHidden.value, statusBarPx.value, currentHeadsUp.value)
    }
    val currentProgress = rememberUpdatedState(progress)
    val currentHeight = rememberUpdatedState(heightPx)
    val glyphs = remember {
        SystemBarGlyphs(
            progress = { currentProgress.value() },
            heightPx = { currentHeight.value() },
            statusBarPx = { statusBarPx.value },
            navBarPx = { navBarPx.value },
            // The own bar fades with home under the rising drawer: its scrim counts as much as is on screen.
            // With the user's own picture behind the drawer, the picture is what shows through instead.
            drawerIsLightOverStatusBar = { homeDim, homeAlpha ->
                val behindHome = dimmedUnderDrawer(
                    behindStatusBarLuminance(ownBarOnScreen(), ownBarScrimOnScreen(currentScrim.value, homeAlpha), wallpaperDarkText.value),
                    homeDim,
                )
                drawerSurfaceIsLight(surfaceLuminance.value, currentDrawerOpacity.value, behindDrawerLuminance(currentPicture.value, behindHome))
            },
            drawerIsLightOverNavBar = { homeDim ->
                val behindHome = dimmedUnderDrawer(if (wallpaperDarkText.value) 1f else 0f, homeDim)
                drawerSurfaceIsLight(surfaceLuminance.value, currentDrawerOpacity.value, behindDrawerLuminance(currentPicture.value, behindHome))
            },
            coverIsLight = { covers.coverIsLight },
            ownBarBacksDark = { homeAlpha -> ownBarBacksDark(ownBarOnScreen(), ownBarScrimOnScreen(currentScrim.value, homeAlpha)) },
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

internal fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
