package org.arkikeskus.launcher.ui

import android.app.WallpaperColors
import androidx.compose.runtime.mutableFloatStateOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Issue #11: in light mode the system status bar drew black clock/signal/battery glyphs on a dark
 * wallpaper. Like Pixel Launcher, the glyphs now follow what is behind them: the wallpaper's own
 * "dark text works on me" hint, ARK's dark status-bar backing when that bar is on, and the drawer's
 * surface once the drawer covers the bar.
 */
class SystemBarContrastTest {

    @Test fun onHomeTheWallpaperDecides() {
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, ownBarShown = false, wallpaperSupportsDarkText = false))
            .isFalse()
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, ownBarShown = false, wallpaperSupportsDarkText = true))
            .isTrue()
    }

    /** ARK's own bar backs the system bar with a dark scrim: dark glyphs would vanish on it. */
    @Test fun onTheLaunchersOwnBarTheGlyphsAreLight() {
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, ownBarShown = true, wallpaperSupportsDarkText = true))
            .isFalse()
    }

    @Test fun overTheDrawerItsSurfaceDecides() {
        assertThat(statusBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = true, ownBarShown = true, wallpaperSupportsDarkText = false))
            .isTrue()
        assertThat(statusBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = false, ownBarShown = false, wallpaperSupportsDarkText = true))
            .isFalse()
    }

    @Test fun theNavigationBarFollowsTheDrawerOrTheWallpaper() {
        assertThat(navigationBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, wallpaperSupportsDarkText = false)).isFalse()
        assertThat(navigationBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = false, wallpaperSupportsDarkText = true)).isTrue()
        assertThat(navigationBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = true, wallpaperSupportsDarkText = false)).isTrue()
        assertThat(navigationBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = false, wallpaperSupportsDarkText = true)).isFalse()
    }

    /** The drawer slides up from the bottom: it reaches the navigation bar almost at once and the status
     *  bar only at the very end. A bar counts as covered once the drawer's edge is past its middle. */
    @Test fun whenTheDrawerCoversEachBar() {
        val height = 2400f
        val statusBar = 130f
        val navBar = 60f

        assertThat(drawerCoversStatusBar(progress = 0f, heightPx = height, barPx = statusBar)).isFalse()
        assertThat(drawerCoversStatusBar(progress = 0.95f, heightPx = height, barPx = statusBar)).isFalse()
        assertThat(drawerCoversStatusBar(progress = 0.98f, heightPx = height, barPx = statusBar)).isTrue()
        assertThat(drawerCoversStatusBar(progress = 1f, heightPx = height, barPx = statusBar)).isTrue()

        assertThat(drawerCoversNavigationBar(progress = 0f, heightPx = height, barPx = navBar)).isFalse()
        assertThat(drawerCoversNavigationBar(progress = 0.01f, heightPx = height, barPx = navBar)).isFalse()
        assertThat(drawerCoversNavigationBar(progress = 0.02f, heightPx = height, barPx = navBar)).isTrue()
    }

    /**
     * The hint covers the whole wallpaper: one that is mostly dark but light at the top gets light
     * glyphs on a light strip. Like Launcher3's top scrim, a soft dark gradient goes behind the system
     * bar whenever its glyphs are light on home, so they read on any wallpaper.
     */
    @Test fun aTopScrimBacksLightGlyphsOnHome() {
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, wallpaperSupportsDarkText = false)).isTrue()
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, wallpaperSupportsDarkText = true)).isFalse()
        // ARK's own bar has its own scrim; a hidden system bar has no glyphs to back.
        assertThat(homeTopScrimShown(ownBarShown = true, systemBarHidden = false, wallpaperSupportsDarkText = false)).isFalse()
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = true, wallpaperSupportsDarkText = false)).isFalse()
        assertThat(HOME_TOP_SCRIM_ALPHA).isAtMost(0.35f)
    }

    /**
     * The bar heights arrive after the first frame (insets are 0 until the window is laid out). The
     * glyph decision must follow them: an effect that kept the first frame's 0 never saw the drawer
     * covering the status bar and left white glyphs on the open, light drawer.
     */
    @Test fun inputsThatArriveLaterAreFollowed() {
        val progress = mutableFloatStateOf(1f)
        val statusBar = mutableFloatStateOf(0f)
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { statusBar.floatValue },
            navBarPx = { 60f },
            surfaceIsLight = { true },
            ownBarShown = { false },
            wallpaperSupportsDarkText = { false },
        )
        assertThat(glyphs.darkStatus.value).isFalse()

        statusBar.floatValue = 130f

        assertThat(glyphs.darkStatus.value).isTrue()
        progress.floatValue = 0f
        assertThat(glyphs.darkStatus.value).isFalse()
        assertThat(glyphs.darkNav.value).isFalse()
    }

    @Test fun theWallpaperHintIsRead() {
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_TEXT)).isTrue()
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_TEXT or WallpaperColors.HINT_SUPPORTS_DARK_THEME)).isTrue()
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_THEME)).isFalse()
        assertThat(supportsDarkText(0)).isFalse()
    }
}
