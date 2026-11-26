package org.arkikeskus.launcher.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.DrawerWallpaper
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.LauncherAppsSource
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.ShownDrawerWallpaper
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.DrawerSurface
import org.arkikeskus.launcher.model.IconEpochs
import org.arkikeskus.launcher.model.WallpaperLook
import org.arkikeskus.launcher.model.drawerSurface
import javax.inject.Inject
import org.arkikeskus.launcher.model.IconShape

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
data class IconStyle(
    val iconPack: String = "",
    val themed: Boolean = false,
    val iconScale: Float = 1f,
    val themeAll: Boolean = false,
    val shape: IconShape = IconShape.SYSTEM,
)

/** System signals and the profile-tagged drawer picture. The shell takes its visual settings
 * from the same loaded frame that supplies the home grid. */
@HiltViewModel
class LauncherShellViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    launcherAppsSource: LauncherAppsSource,
    notificationBadgeRepository: NotificationBadgeRepository,
    drawerWallpaper: DrawerWallpaperStore,
) : ViewModel() {
    /** A heads-up notification's bar reveal is on: home blanks its own status bar for the window (see
     *  [NotificationBadgeRepository.headsUpActive]), so the bar is no dark backing for the glyphs then. */
    val headsUpActive: StateFlow<Boolean> = notificationBadgeRepository.headsUpActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val iconEpochs: StateFlow<IconEpochs> = launcherAppsSource.iconEpochs

    /** The theme and bars can start as soon as preferences arrive, before Room has supplied home. */
    val settings = settingsRepository.settings
    val drawerPicture = drawerWallpaper.shown

    suspend fun rememberWallpaper(look: WallpaperLook) {
        try {
            settingsRepository.setLastHomeWallpaper(look)
        } catch (_: java.io.IOException) {
            // The current screen already has the live report. Keep the previous result on disk.
        }
    }
}

/**
 * [StatusBarSettings] of [settings] and the drawer picture the store shows ([shown]). The picture counts only
 * while it is the picture of the home profile the settings are of: the store follows a profile switch a moment
 * late, and the previous profile's picture says nothing of this profile's drawer.
 */
internal fun statusBarSettings(settings: LauncherSettings, shown: ShownDrawerWallpaper): StatusBarSettings {
    val picture = shown.of(settings.homeProfileId)
    return StatusBarSettings(
        ownBarShown = settings.showStatusBar,
        systemBarHidden = settings.hideSystemStatusBar,
        scrimOpacity = settings.statusBarScrimOpacity,
        drawerSurface = drawerSurface(
            settings.drawerBackgroundColor, settings.drawerBackgroundOpacity, picture != null, settings.drawerPictureDim,
        ),
        drawerPicture = picture,
        glassOpacity = if (settings.homeGlassPanel) settings.homeGlassOpacity else null,
    )
}

/** The wallpaper blur behind the rising drawer: on or off, and its radius as a multiple of the 0.9.9 blur. */
data class DrawerBlur(val enabled: Boolean = false, val strength: Float = 1f)
