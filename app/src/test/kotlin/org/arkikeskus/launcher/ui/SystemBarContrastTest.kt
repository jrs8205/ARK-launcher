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

    /** A see-through drawer leaves what is behind it behind the bars: that decides, the surface only as opaque as it is. */
    @Test fun aSeeThroughDrawerLetsWhatIsBehindItDecide() {
        // Opaque: the surface as before, whatever is behind.
        assertThat(drawerSurfaceIsLight(surfaceLuminance = 0.98f, opacity = 1f, behindLuminance = 0f)).isTrue()
        assertThat(drawerSurfaceIsLight(surfaceLuminance = 0.01f, opacity = 1f, behindLuminance = 1f)).isFalse()
        // Fully transparent: what is behind.
        assertThat(drawerSurfaceIsLight(surfaceLuminance = 0.98f, opacity = 0f, behindLuminance = 0f)).isFalse()
        assertThat(drawerSurfaceIsLight(surfaceLuminance = 0.01f, opacity = 0f, behindLuminance = 1f)).isTrue()
        // Half: a light theme over a dark wallpaper is still dark enough for light glyphs ...
        assertThat(drawerSurfaceIsLight(surfaceLuminance = 0.98f, opacity = 0.4f, behindLuminance = 0f)).isFalse()
        // ... and flips once the surface dominates.
        assertThat(drawerSurfaceIsLight(surfaceLuminance = 0.98f, opacity = 0.7f, behindLuminance = 0f)).isTrue()
    }

    /** The own bar's scrim counts only as strong as it is: the default 60 % over a white wallpaper leaves a mid grey
     *  (about 0.4), and a half-transparent light drawer over that is light enough for dark glyphs; a solid scrim is black. */
    @Test fun theOwnBarsScrimCountsOnlyAsStrongAsItIs() {
        assertThat(behindStatusBarLuminance(ownBarVisible = true, scrimOpacity = 0.6f, wallpaperSupportsDarkText = true)).isWithin(0.001f).of(0.4f)
        assertThat(behindStatusBarLuminance(ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true)).isEqualTo(0f)
        assertThat(behindStatusBarLuminance(ownBarVisible = false, scrimOpacity = 1f, wallpaperSupportsDarkText = true)).isEqualTo(1f)
        assertThat(behindStatusBarLuminance(ownBarVisible = true, scrimOpacity = 0.6f, wallpaperSupportsDarkText = false)).isEqualTo(0f)
        // Light theme, white wallpaper, the default scrim, a half-transparent drawer: dark glyphs, as before the setting.
        assertThat(drawerIsLightOverStatusBar(0.98f, opacity = 0.5f, ownBarVisible = true, scrimOpacity = 0.6f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        // The same over a solid scrim: light glyphs.
        assertThat(drawerIsLightOverStatusBar(0.98f, opacity = 0.5f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = 0f)).isFalse()
    }

    /** Home blanks its own bar while the system bar it was to replace is on top (a kept bar that takes layout space, or a
     *  heads-up reveal): the blanked bar is no dark backing, over the drawer or on home. */
    @Test fun aBlankedOwnBarIsNoDarkBacking() {
        assertThat(ownBarVisible(ownBarShown = true, systemBarHidden = true, statusBarPx = 130f, headsUpActive = false)).isFalse()
        assertThat(ownBarVisible(ownBarShown = true, systemBarHidden = true, statusBarPx = 0f, headsUpActive = true)).isFalse()
        assertThat(ownBarVisible(ownBarShown = true, systemBarHidden = true, statusBarPx = 0f, headsUpActive = false)).isTrue()
        assertThat(ownBarVisible(ownBarShown = true, systemBarHidden = false, statusBarPx = 130f, headsUpActive = true)).isTrue()
        assertThat(ownBarVisible(ownBarShown = false, systemBarHidden = true, statusBarPx = 0f, headsUpActive = false)).isFalse()

        // White wallpaper, the own bar on with a solid scrim but blanked (system bar hidden by the setting, kept by the
        // system: 130 px of inset), the drawer transparent: dark glyphs both open and on home.
        val progress = mutableFloatStateOf(1f)
        val visible = { ownBarVisible(ownBarShown = true, systemBarHidden = true, statusBarPx = 130f, headsUpActive = false) }
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { drawerIsLightOverStatusBar(0.01f, 0f, visible(), 1f, wallpaperSupportsDarkText = true, homeDim = it) },
            drawerIsLightOverNavBar = { drawerIsLightOverNavigationBar(0.01f, 0f, wallpaperSupportsDarkText = true, homeDim = it) },
            coverIsLight = { null },
            ownBarBacksDark = { ownBarBacksDark(visible(), 1f) },
            wallpaperSupportsDarkText = { true },
        )
        assertThat(glyphs.darkStatus.value).isTrue()
        progress.floatValue = 0f
        assertThat(glyphs.darkStatus.value).isTrue()
    }

    /** Behind a see-through drawer, over the status bar, is ARK's own bar with its dark scrim when that is on: a dark
     *  theme's transparent drawer over a LIGHT wallpaper keeps light glyphs there (the scrim is black), while the
     *  navigation bar, over the wallpaper, gets dark ones. */
    @Test fun aSeeThroughDrawerOverTheOwnDarkBarKeepsLightStatusGlyphs() {
        val darkTheme = 0.01f
        assertThat(drawerIsLightOverStatusBar(darkTheme, opacity = 0f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = 0f)).isFalse()
        assertThat(drawerIsLightOverStatusBar(darkTheme, opacity = 0f, ownBarVisible = false, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        assertThat(drawerIsLightOverNavigationBar(darkTheme, opacity = 0f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        // Opaque, the own bar is covered: the surface decides as before.
        assertThat(drawerIsLightOverStatusBar(0.98f, opacity = 1f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = false, homeDim = 0f)).isTrue()

        // The whole decision, as the shell wires it: showStatusBar on, scrim 1.0, dark theme, drawer transparent and open.
        val ownBar = ownBarBacksDark(ownBarShown = true, scrimOpacity = 1f)
        val glyphs = SystemBarGlyphs(
            progress = { 1f },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { drawerIsLightOverStatusBar(darkTheme, 0f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = it) },
            drawerIsLightOverNavBar = { drawerIsLightOverNavigationBar(darkTheme, 0f, wallpaperSupportsDarkText = true, homeDim = it) },
            coverIsLight = { null },
            ownBarBacksDark = { ownBar },
            wallpaperSupportsDarkText = { true },
        )
        assertThat(glyphs.darkStatus.value).isFalse() // light glyphs on the own bar's black
        assertThat(glyphs.darkNav.value).isTrue() // dark glyphs on the light wallpaper
    }

    /** The shell dims home under the rising drawer ([drawerScrimAlpha], 32 % when open), so behind a see-through
     *  drawer is home's top edge DIMMED by that -- over the status bar and the navigation bar alike. White wallpaper,
     *  light theme, the own bar at 50 %, the drawer at 5 %: 0.98 x 0.05 + (1 x 0.5 x 0.68) x 0.95 = 0.37 -> light
     *  glyphs, where the undimmed 0.52 chose dark ones. Opaque, the surface still decides. */
    @Test fun theDimOverHomeUnderTheDrawerCountsInTheBackdrop() {
        val lightTheme = 0.98f
        val open = drawerScrimAlpha(1f)
        assertThat(drawerIsLightOverStatusBar(lightTheme, 0.05f, ownBarVisible = true, scrimOpacity = 0.5f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        assertThat(drawerIsLightOverStatusBar(lightTheme, 0.05f, ownBarVisible = true, scrimOpacity = 0.5f, wallpaperSupportsDarkText = true, homeDim = open)).isFalse()
        // The navigation bar: a dark theme at 40 % over the white wallpaper is light undimmed (0.60), dark dimmed (0.41).
        assertThat(drawerIsLightOverNavigationBar(0.01f, 0.4f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        assertThat(drawerIsLightOverNavigationBar(0.01f, 0.4f, wallpaperSupportsDarkText = true, homeDim = open)).isFalse()
        // Opaque: the dim is behind the drawer, the surface decides.
        assertThat(drawerIsLightOverStatusBar(lightTheme, 1f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = false, homeDim = open)).isTrue()
        assertThat(drawerIsLightOverNavigationBar(0.01f, 1f, wallpaperSupportsDarkText = true, homeDim = open)).isFalse()

        // The whole decision, as the shell wires it: the dim comes from the drawer's progress inside the snapshot.
        val progress = mutableFloatStateOf(1f)
        val dimsSeen = mutableListOf<Float>()
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { dim ->
                dimsSeen += dim
                drawerIsLightOverStatusBar(lightTheme, 0.05f, ownBarVisible = true, scrimOpacity = 0.5f, wallpaperSupportsDarkText = true, homeDim = dim)
            },
            drawerIsLightOverNavBar = { dim -> drawerIsLightOverNavigationBar(0.01f, 0.4f, wallpaperSupportsDarkText = true, homeDim = dim) },
            coverIsLight = { null },
            ownBarBacksDark = { ownBarBacksDark(ownBarShown = true, scrimOpacity = 0.5f) },
            wallpaperSupportsDarkText = { true },
        )
        assertThat(glyphs.darkStatus.value).isFalse()
        assertThat(glyphs.darkNav.value).isFalse()
        assertThat(dimsSeen).containsExactly(open)
        // Home again: the own bar's scrim backs light glyphs, the wallpaper's hint the navigation bar.
        progress.floatValue = 0f
        assertThat(glyphs.darkStatus.value).isFalse()
        assertThat(glyphs.darkNav.value).isTrue()
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

        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, statusBarPx = 130f, headsUpActive = false, wallpaperSupportsDarkText = false, covered = true)).isFalse()
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
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, statusBarPx = 130f, headsUpActive = false, wallpaperSupportsDarkText = false, covered = false)).isTrue()
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, statusBarPx = 130f, headsUpActive = false, wallpaperSupportsDarkText = true, covered = false)).isFalse()
        // ARK's own bar has its own scrim; a hidden system bar has no glyphs to back.
        assertThat(homeTopScrimShown(ownBarShown = true, systemBarHidden = false, statusBarPx = 130f, headsUpActive = false, wallpaperSupportsDarkText = false, covered = false)).isFalse()
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = true, statusBarPx = 0f, headsUpActive = false, wallpaperSupportsDarkText = false, covered = false)).isFalse()
        assertThat(HOME_TOP_SCRIM_ALPHA).isAtMost(0.35f)
    }

    /** The scrim follows the bars as they really are, like the glyph rules ([ownBarVisible]): the own bar blanked
     *  under a system bar the system keeps (an inset while hidden by the setting) or reveals for a heads-up has
     *  no scrim of its own, and the system bar's light glyphs on a dark wallpaper get the gradient again. */
    @Test fun theTopScrimBacksAKeptOrRevealedSystemBarOverABlankedOwnBar() {
        assertThat(homeTopScrimShown(ownBarShown = true, systemBarHidden = true, statusBarPx = 130f, headsUpActive = false, wallpaperSupportsDarkText = false, covered = false)).isTrue()
        assertThat(homeTopScrimShown(ownBarShown = true, systemBarHidden = true, statusBarPx = 0f, headsUpActive = true, wallpaperSupportsDarkText = false, covered = false)).isTrue()
        // Really hidden, or the own bar really on screen: as before.
        assertThat(homeTopScrimShown(ownBarShown = true, systemBarHidden = true, statusBarPx = 0f, headsUpActive = false, wallpaperSupportsDarkText = false, covered = false)).isFalse()
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = true, statusBarPx = 130f, headsUpActive = false, wallpaperSupportsDarkText = true, covered = false)).isFalse()
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
            drawerIsLightOverStatusBar = { true },
            drawerIsLightOverNavBar = { true },
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
