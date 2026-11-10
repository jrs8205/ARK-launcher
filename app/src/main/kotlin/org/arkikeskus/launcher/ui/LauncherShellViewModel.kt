package org.arkikeskus.launcher.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.LauncherAppsSource
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.model.DrawerSurface
import org.arkikeskus.launcher.model.IconEpochs
import org.arkikeskus.launcher.model.drawerSurface
import javax.inject.Inject

/** ARK's own status bar is on, with its scrim at [scrimOpacity]; the system bar is hidden on home; the app
 *  drawer paints [drawerSurface] over what is behind it (a see-through drawer leaves the wallpaper behind the bars). */
data class StatusBarSettings(
    val ownBarShown: Boolean = false,
    val systemBarHidden: Boolean = false,
    val scrimOpacity: Float = 0.6f,
    /** What the drawer paints and how much of it shows: its lightness, weighted by the opacity, decides the glyphs. */
    val drawerSurface: DrawerSurface = DrawerSurface(tint = null, paint = null, opacity = 1f),
    /** The user's own picture behind the drawer, or null without one: with a picture it is what a see-through drawer
     *  lets through, in place of home and the wallpaper -- its lightness for the window's crop of it
     *  ([DrawerWallpaper.luminanceFor]). */
    val drawerPicture: DrawerWallpaper? = null,
    /** The strength of the glass layer over the wallpaper on home, or null without it: a white tint behind the bars
     *  too, which a strong setting makes light enough for dark glyphs ([homeBackdropSupportsDarkGlyphs]). */
    val glassOpacity: Float? = null,
)

/** The app-icon style (icon pack, themed, the icon-size multiplier), so the shell's floating drag icon
 *  matches the surfaces. */
data class IconStyle(val iconPack: String = "", val themed: Boolean = false, val iconScale: Float = 1f, val themeAll: Boolean = false)

/**
 * Exposes just the icon-style settings to [LauncherShell] so the single floating drag icon — drawn
 * above the home and drawer, outside their own CompositionLocal scopes — renders with the selected
 * icon pack / themed icons instead of the default system icon. Also relays the icon re-fetch epochs
 * (see [org.arkikeskus.launcher.model.IconEpochs]) that the shell provides to every icon surface.
 */
@HiltViewModel
class LauncherShellViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    launcherAppsSource: LauncherAppsSource,
    notificationBadgeRepository: NotificationBadgeRepository,
    drawerWallpaper: DrawerWallpaperStore,
) : ViewModel() {
    /** A heads-up notification's bar reveal is on: home blanks its own status bar for the window (see
     *  [NotificationBadgeRepository.headsUpActive]), so the bar is no dark backing for the glyphs then. */
    val headsUpActive: StateFlow<Boolean> = notificationBadgeRepository.headsUpActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val iconStyle: StateFlow<IconStyle> = settingsRepository.settings
        .map { IconStyle(it.iconPackPackage, it.useThemedIcons, it.iconScale, it.themeAllIcons) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IconStyle())

    val iconEpochs: StateFlow<IconEpochs> = launcherAppsSource.iconEpochs

    /** The status-bar settings the system bars' glyph colour depends on (see SystemBarContrast). */
    val statusBars: StateFlow<StatusBarSettings> = settingsRepository.settings
        .combine(drawerWallpaper.current) { it, picture ->
            StatusBarSettings(
                ownBarShown = it.showStatusBar,
                systemBarHidden = it.hideSystemStatusBar,
                scrimOpacity = it.statusBarScrimOpacity,
                drawerSurface = drawerSurface(it.drawerBackgroundColor, it.drawerBackgroundOpacity, picture != null, it.drawerPictureDim),
                drawerPicture = picture,
                glassOpacity = if (it.homeGlassPanel) it.homeGlassOpacity else null,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusBarSettings())

    /** The long-press delay setting, applied once at the shell root for every press site under it. */
    val longPressScale: StateFlow<Float> = settingsRepository.settings
        .map { it.longPressScale }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    /** The wallpaper blurs behind the rising drawer (Settings > App drawer, opt-in), at the chosen strength. */
    val drawerBlur: StateFlow<DrawerBlur> = settingsRepository.settings
        .map { DrawerBlur(enabled = it.blurBehindDrawer, strength = it.drawerBlurStrength) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DrawerBlur())
}

/** The wallpaper blur behind the rising drawer: on or off, and its radius as a multiple of the 0.9.9 blur. */
data class DrawerBlur(val enabled: Boolean = false, val strength: Float = 1f)
