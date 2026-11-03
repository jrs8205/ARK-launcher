package org.arkikeskus.launcher.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** The blur at a fully open drawer and 100 % strength (the 0.9.9 blur). */
internal val DRAWER_BLUR_MAX = 24.dp

/** [basePx] scaled by the strength setting (Settings > App drawer), in whole pixels and at least one. */
internal fun drawerBlurMaxPx(basePx: Int, strength: Float): Int =
    (basePx * strength).roundToInt().coerceAtLeast(1)

/**
 * The window's background blur radius for the drawer at [progress] (0 closed, 1 open): nothing while
 * the drawer is parked, [maxPx] when it is open, in whole pixels between; and nothing at all when the
 * setting is off ([enabled]) or the system cannot blur ([supported], Android 12 and newer).
 */
internal fun drawerBlurRadiusPx(progress: Float, maxPx: Int, enabled: Boolean = true, supported: Boolean = true): Int {
    if (!enabled || !supported) return 0
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.001f) return 0
    return (p * maxPx).roundToInt()
}

/**
 * Blurs the wallpaper behind the launcher window as the drawer rises (a Telegram request, opt-in):
 * the window shows the wallpaper through its transparent background, and Android 12's background
 * blur softens exactly that, so the home icons and the drawer stay sharp. The system honours the
 * radius only while cross-window blur is allowed (not in battery saver, and not on every ROM); the
 * radius is set on every change and cleared when the shell goes.
 */
@Composable
internal fun DrawerWallpaperBlur(enabled: Boolean, strength: Float, progress: () -> Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val maxPx = with(LocalDensity.current) { drawerBlurMaxPx(DRAWER_BLUR_MAX.roundToPx(), strength) }
    LaunchedEffect(window, enabled, maxPx) {
        snapshotFlow { drawerBlurRadiusPx(progress(), maxPx, enabled) }
            .distinctUntilChanged()
            .collect { radius -> window.setBackgroundBlurRadius(radius) }
    }
    DisposableEffect(window) {
        onDispose { window.setBackgroundBlurRadius(0) }
    }
}
