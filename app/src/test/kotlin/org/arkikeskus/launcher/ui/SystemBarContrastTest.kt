package org.arkikeskus.launcher.ui

import android.app.WallpaperColors
import androidx.compose.runtime.mutableFloatStateOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Issue #11: in light mode the system status bar drew black clock/signal/battery glyphs on a dark
 * wallpaper. Like Pixel Launcher, the glyphs now follow what is behind them: the wallpaper's own
 * "dark text works on me" hint, ARK's dark status-bar backing when that bar is on, and the drawer's
 * surface once the drawer covers the bar.
 */
class SystemBarContrastTest {

    @Test fun onHomeTheWallpaperDecides() {
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, coverIsLight = null, ownBarBacksDark = false, wallpaperSupportsDarkText = false))
            .isFalse()
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, coverIsLight = null, ownBarBacksDark = false, wallpaperSupportsDarkText = true))
            .isTrue()
    }

    /** ARK's own bar backs the system bar with a dark scrim: dark glyphs would vanish on it. */
    @Test fun onTheLaunchersOwnBarTheGlyphsAreLight() {
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, coverIsLight = null, ownBarBacksDark = true, wallpaperSupportsDarkText = true))
            .isFalse()
    }

    /**
     * ARK's bar backs the system bar with its scrim at the opacity the user picked. A weak scrim is no
     * dark backing: forcing light glyphs there put white glyphs on a light wallpaper, so the
     * wallpaper's hint decides instead.
     */
    @Test fun aWeakOwnBarScrimIsNoDarkBacking() {
        assertThat(ownBarBacksDark(ownBarShown = true, scrimOpacity = 0.6f)).isTrue()
        assertThat(ownBarBacksDark(ownBarShown = true, scrimOpacity = OWN_BAR_DARK_BACKING)).isTrue()
        assertThat(ownBarBacksDark(ownBarShown = true, scrimOpacity = 0f)).isFalse()
        assertThat(ownBarBacksDark(ownBarShown = true, scrimOpacity = 0.3f)).isFalse()
        assertThat(ownBarBacksDark(ownBarShown = false, scrimOpacity = 1f)).isFalse()
        // A transparent own bar over a light wallpaper: dark glyphs, as without the bar.
        val weak = ownBarBacksDark(ownBarShown = true, scrimOpacity = 0f)
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = false, coverIsLight = null, ownBarBacksDark = weak, wallpaperSupportsDarkText = true))
            .isTrue()
    }

    @Test fun overTheDrawerItsSurfaceDecides() {
        assertThat(statusBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = true, coverIsLight = null, ownBarBacksDark = true, wallpaperSupportsDarkText = false))
            .isTrue()
        assertThat(statusBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = false, coverIsLight = null, ownBarBacksDark = false, wallpaperSupportsDarkText = true))
            .isFalse()
    }

    /**
     * A full-screen surface over home — the widget picker, the first-run intro — hides the wallpaper
     * and ARK's own bar: both bars' glyphs follow it, until the drawer covers a bar in turn. The top
     * gradient is not drawn over it.
     */
    @Test fun aSurfaceCoveringHomeDecides() {
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, coverIsLight = false, ownBarBacksDark = false, wallpaperSupportsDarkText = true))
            .isFalse()
        assertThat(statusBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = false, coverIsLight = true, ownBarBacksDark = true, wallpaperSupportsDarkText = false))
            .isTrue()
        assertThat(statusBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = false, coverIsLight = true, ownBarBacksDark = false, wallpaperSupportsDarkText = true))
            .isFalse()

        assertThat(navigationBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, coverIsLight = false, wallpaperSupportsDarkText = true)).isFalse()
        assertThat(navigationBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = false, coverIsLight = true, wallpaperSupportsDarkText = false)).isTrue()
        assertThat(navigationBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = false, coverIsLight = true, wallpaperSupportsDarkText = true)).isFalse()

        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, wallpaperSupportsDarkText = false, covered = true)).isFalse()
    }

    @Test fun theNavigationBarFollowsTheDrawerOrTheWallpaper() {
        assertThat(navigationBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = true, coverIsLight = null, wallpaperSupportsDarkText = false)).isFalse()
        assertThat(navigationBarDarkIcons(drawerCoversBar = false, drawerSurfaceIsLight = false, coverIsLight = null, wallpaperSupportsDarkText = true)).isTrue()
        assertThat(navigationBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = true, coverIsLight = null, wallpaperSupportsDarkText = false)).isTrue()
        assertThat(navigationBarDarkIcons(drawerCoversBar = true, drawerSurfaceIsLight = false, coverIsLight = null, wallpaperSupportsDarkText = true)).isFalse()
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
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, wallpaperSupportsDarkText = false, covered = false)).isTrue()
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, wallpaperSupportsDarkText = true, covered = false)).isFalse()
        // ARK's own bar has its own scrim; a hidden system bar has no glyphs to back.
        assertThat(homeTopScrimShown(ownBarShown = true, systemBarHidden = false, wallpaperSupportsDarkText = false, covered = false)).isFalse()
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = true, wallpaperSupportsDarkText = false, covered = false)).isFalse()
        assertThat(HOME_TOP_SCRIM_ALPHA).isAtMost(0.35f)
    }

    /**
     * The scrim stays at full strength until the drawer covers the status bar, which is also when the
     * glyphs switch to the drawer's colour: fading it with the drawer's whole travel left light glyphs
     * on an almost bare light strip at the top while the drawer was still below the bar.
     */
    @Test fun theTopScrimStaysUntilTheDrawerCoversTheBar() {
        val height = 2400f
        val statusBar = 130f

        assertThat(homeTopScrimAlpha(progress = 0f, heightPx = height, barPx = statusBar)).isEqualTo(HOME_TOP_SCRIM_ALPHA)
        assertThat(homeTopScrimAlpha(progress = 0.95f, heightPx = height, barPx = statusBar)).isEqualTo(HOME_TOP_SCRIM_ALPHA)
        assertThat(homeTopScrimAlpha(progress = 0.98f, heightPx = height, barPx = statusBar)).isEqualTo(0f)
        assertThat(homeTopScrimAlpha(progress = 1f, heightPx = height, barPx = statusBar)).isEqualTo(0f)
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
            coverIsLight = { null },
            ownBarBacksDark = { false },
            wallpaperSupportsDarkText = { false },
        )
        assertThat(glyphs.darkStatus.value).isFalse()

        statusBar.floatValue = 130f

        assertThat(glyphs.darkStatus.value).isTrue()
        progress.floatValue = 0f
        assertThat(glyphs.darkStatus.value).isFalse()
        assertThat(glyphs.darkNav.value).isFalse()
    }

    /**
     * The first read may take a while; a wallpaper (or live-wallpaper colour) change reported by the
     * listener meanwhile is newer, and the first read's older answer must not overwrite it.
     */
    @Test fun aColourChangeDuringTheFirstReadIsNotOverwrittenByIt() = runBlocking {
        val wallpaper = WallpaperDarkText()
        val firstRead = CompletableDeferred<Boolean>()
        val reading = launch(start = CoroutineStart.UNDISPATCHED) { wallpaper.readInitial { firstRead.await() } }

        wallpaper.onColorsChanged(true)
        firstRead.complete(false)
        reading.join()

        assertThat(wallpaper.state.value).isTrue()
    }

    @Test fun theFirstReadLandsWhenNothingNewerCame() = runBlocking {
        val wallpaper = WallpaperDarkText()

        wallpaper.readInitial { true }

        assertThat(wallpaper.state.value).isTrue()
        wallpaper.onColorsChanged(false)
        assertThat(wallpaper.state.value).isFalse()
    }

    @Test fun theWallpaperHintIsRead() {
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_TEXT)).isTrue()
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_TEXT or WallpaperColors.HINT_SUPPORTS_DARK_THEME)).isTrue()
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_THEME)).isFalse()
        assertThat(supportsDarkText(0)).isFalse()
    }
}
