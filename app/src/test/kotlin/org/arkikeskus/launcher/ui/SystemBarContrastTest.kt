package org.arkikeskus.launcher.ui

import android.app.WallpaperColors
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableFloatStateOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.arkikeskus.launcher.model.DrawerSurface
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.drawerSurface
import org.arkikeskus.launcher.model.glassTint
import org.junit.Test

/**
 * Issue #11: in light mode the system status bar drew black clock/signal/battery glyphs on a dark
 * wallpaper. Like Pixel Launcher, the glyphs now follow what is behind them: the wallpaper's own
 * "dark text works on me" hint, ARK's dark status-bar backing when that bar is on, and the drawer's
 * surface once the drawer covers the bar.
 */
class SystemBarContrastTest {
    private val lightTheme = Color(0xFFFBFCFF)
    private val darkTheme = Color(0xFF111318)

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
        assertThat(drawerSurfaceIsLight(surface = lightTheme, opacity = 1f, behindLuminance = 0f)).isTrue()
        assertThat(drawerSurfaceIsLight(surface = darkTheme, opacity = 1f, behindLuminance = 1f)).isFalse()
        // Fully transparent: what is behind.
        assertThat(drawerSurfaceIsLight(surface = lightTheme, opacity = 0f, behindLuminance = 0f)).isFalse()
        assertThat(drawerSurfaceIsLight(surface = darkTheme, opacity = 0f, behindLuminance = 1f)).isTrue()
        // Half: a light theme over a dark wallpaper is still dark enough for light glyphs ...
        assertThat(drawerSurfaceIsLight(surface = lightTheme, opacity = 0.4f, behindLuminance = 0f)).isFalse()
        // ... and flips once the surface dominates.
        assertThat(drawerSurfaceIsLight(surface = lightTheme, opacity = 0.7f, behindLuminance = 0f)).isTrue()
    }

    /** The own bar's scrim counts only as strong as it is, as the screen shows it: the default 60 % over a white wallpaper
     *  leaves sRGB 0.4 (relative luminance 0.13, not the linear 0.4), and a half-transparent light drawer over that is
     *  light enough for dark glyphs; a solid scrim is black, and over it the drawer must bring the light itself: at
     *  30 % a dark grey (0.07, light glyphs), at 50 % sRGB 0.5 (0.21), where dark glyphs read better -- as the drawer's
     *  own labels do on that grey. */
    @Test fun theOwnBarsScrimCountsOnlyAsStrongAsItIs() {
        assertThat(behindStatusBarLuminance(ownBarVisible = true, scrimOpacity = 0.6f, wallpaperSupportsDarkText = true)).isWithin(0.01f).of(0.13f)
        assertThat(behindStatusBarLuminance(ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true)).isEqualTo(0f)
        assertThat(behindStatusBarLuminance(ownBarVisible = false, scrimOpacity = 1f, wallpaperSupportsDarkText = true)).isEqualTo(1f)
        assertThat(behindStatusBarLuminance(ownBarVisible = true, scrimOpacity = 0.6f, wallpaperSupportsDarkText = false)).isEqualTo(0f)
        // Light theme, white wallpaper, the default scrim, a half-transparent drawer: dark glyphs, as before the setting.
        assertThat(drawerIsLightOverStatusBar(lightTheme, opacity = 0.5f, ownBarVisible = true, scrimOpacity = 0.6f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        // Over a solid scrim: light glyphs at 30 %, dark ones at 50 %.
        assertThat(drawerIsLightOverStatusBar(lightTheme, opacity = 0.3f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = 0f)).isFalse()
        assertThat(drawerIsLightOverStatusBar(lightTheme, opacity = 0.5f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
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
            drawerIsLightOverStatusBar = { dim, _ -> drawerIsLightOverStatusBar(darkTheme, 0f, visible(), 1f, wallpaperSupportsDarkText = true, homeDim = dim) },
            drawerIsLightOverNavBar = { dim, _ -> drawerIsLightOverNavigationBar(darkTheme, 0f, wallpaperSupportsDarkText = true, homeDim = dim) },
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
        assertThat(drawerIsLightOverStatusBar(darkTheme, opacity = 0f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = 0f)).isFalse()
        assertThat(drawerIsLightOverStatusBar(darkTheme, opacity = 0f, ownBarVisible = false, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        assertThat(drawerIsLightOverNavigationBar(darkTheme, opacity = 0f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        // Opaque, the own bar is covered: the surface decides as before.
        assertThat(drawerIsLightOverStatusBar(lightTheme, opacity = 1f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = false, homeDim = 0f)).isTrue()

        // The whole decision, as the shell wires it: showStatusBar on, scrim 1.0, dark theme, drawer transparent and open
        // -- with the own bar NOT fading (the providers ignore home's alpha here; the fade has its own test below).
        val ownBar = ownBarBacksDark(ownBarShown = true, scrimOpacity = 1f)
        val glyphs = SystemBarGlyphs(
            progress = { 1f },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { dim, _ -> drawerIsLightOverStatusBar(darkTheme, 0f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = true, homeDim = dim) },
            drawerIsLightOverNavBar = { dim, _ -> drawerIsLightOverNavigationBar(darkTheme, 0f, wallpaperSupportsDarkText = true, homeDim = dim) },
            coverIsLight = { null },
            ownBarBacksDark = { ownBar },
            wallpaperSupportsDarkText = { true },
        )
        assertThat(glyphs.darkStatus.value).isFalse() // light glyphs on the own bar's black
        assertThat(glyphs.darkNav.value).isTrue() // dark glyphs on the light wallpaper
    }

    /** The user's own picture behind the drawer sits inside the drawer and covers home and the wallpaper alike, so
     *  it is what a see-through drawer lets through, over both bars; without one, home as the shell shows it. */
    @Test fun anOwnPictureBehindTheDrawerIsWhatShowsThrough() {
        assertThat(behindDrawerLuminance(pictureLuminance = null, behindHome = 0.4f)).isEqualTo(0.4f)
        assertThat(behindDrawerLuminance(pictureLuminance = 0.9f, behindHome = 0.4f)).isEqualTo(0.9f)
        assertThat(behindDrawerLuminance(pictureLuminance = 2f, behindHome = 0.4f)).isEqualTo(1f)
        // A dark theme's transparent drawer: over a light picture dark glyphs, over a dark one light glyphs, whatever
        // the own bar or the wallpaper would have said.
        assertThat(drawerSurfaceIsLight(darkTheme, 0f, behindDrawerLuminance(0.9f, behindHome = 0f))).isTrue()
        assertThat(drawerSurfaceIsLight(darkTheme, 0f, behindDrawerLuminance(0.1f, behindHome = 1f))).isFalse()
    }

    /** The owner's 1.0.0 note: with an own picture the colour is off and the drawer paints a black dim of the user's
     *  strength instead -- undimmed, the picture decides the glyphs; dimmed hard, even a white picture takes light
     *  glyphs. "Transparent" paints nothing, so what is behind decides whatever the theme is. */
    @Test fun anOwnPictureIsDimmedByItsOwnSettingNotTheColour() {
        val yellow = 0xFFFFEE58.toInt()
        val whitePicture = 1f
        fun glyphsDark(surface: DrawerSurface, picture: Float?) =
            drawerSurfaceIsLight(drawerSurfaceColor(surface.paint, lightTheme), surface.opacity, behindDrawerLuminance(picture, behindHome = 0f))

        assertThat(glyphsDark(drawerSurface(yellow, colorOpacity = 1f, hasPicture = true, pictureDim = 0f), whitePicture)).isTrue()
        assertThat(glyphsDark(drawerSurface(yellow, colorOpacity = 1f, hasPicture = true, pictureDim = 0.8f), whitePicture)).isFalse()
        assertThat(glyphsDark(drawerSurface(LauncherSettings.DRAWER_COLOR_TRANSPARENT, colorOpacity = 1f, hasPicture = false, pictureDim = 0f), picture = null)).isFalse()
    }

    /** Home -- and ARK's own bar with it -- fades out under the rising drawer (homeContentAlpha), so the own bar's
     *  scrim backs the glyphs with only as much darkness as is left on screen. White wallpaper, the own bar at a
     *  solid scrim, a dark theme's drawer fully transparent: light glyphs on home, dark ones once the bar has faded,
     *  both while the drawer is still on its way and once it covers the status bar (where the 1.0.0 fade would have
     *  left white glyphs on the bare wallpaper). */
    @Test fun theOwnBarFadesWithHomeUnderTheRisingDrawer() {
        assertThat(ownBarScrimOnScreen(scrimOpacity = 1f, homeAlpha = 1f)).isEqualTo(1f)
        assertThat(ownBarScrimOnScreen(scrimOpacity = 0.6f, homeAlpha = 0.5f)).isWithin(1e-6f).of(0.3f)
        assertThat(ownBarScrimOnScreen(scrimOpacity = 1f, homeAlpha = 0f)).isEqualTo(0f)
        assertThat(ownBarScrimOnScreen(scrimOpacity = 2f, homeAlpha = -1f)).isEqualTo(0f)

        val progress = mutableFloatStateOf(0f)
        val alphasSeen = mutableListOf<Float>()
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { dim, alpha ->
                alphasSeen += alpha
                drawerIsLightOverStatusBar(
                    darkTheme, 0f, ownBarVisible = true, scrimOpacity = ownBarScrimOnScreen(1f, alpha), wallpaperSupportsDarkText = true, homeDim = dim,
                )
            },
            drawerIsLightOverNavBar = { dim, _ -> drawerIsLightOverNavigationBar(darkTheme, 0f, wallpaperSupportsDarkText = true, homeDim = dim) },
            coverIsLight = { null },
            ownBarBacksDark = { alpha -> ownBarBacksDark(ownBarShown = true, scrimOpacity = ownBarScrimOnScreen(1f, alpha)) },
            wallpaperSupportsDarkText = { true },
        )
        assertThat(glyphs.darkStatus.value).isFalse() // on home the own bar's black backs light glyphs
        progress.floatValue = HOME_FADE_SPAN / 2 // half faded: a solid scrim at 50 % still counts as dark backing
        assertThat(glyphs.darkStatus.value).isFalse()
        progress.floatValue = 0.8f // faded out, the drawer not yet over the bar: the white wallpaper decides
        assertThat(glyphs.darkStatus.value).isTrue()
        progress.floatValue = 1f // open: behind the transparent drawer is the bare, dimmed wallpaper, still light
        assertThat(glyphs.darkStatus.value).isTrue()
        assertThat(alphasSeen.last()).isEqualTo(0f)
    }

    /** The shell dims home under the rising drawer ([drawerScrimAlpha], 32 % when open), so behind a see-through
     *  drawer is home's top edge DIMMED by that -- over the status bar and the navigation bar alike. White wallpaper,
     *  light theme, the own bar at 50 %, the drawer at 5 %: on screen sRGB 0.05 x 0.99 + 0.95 x (0.5 x 0.68) = 0.37 =
     *  relative luminance 0.11 -> light glyphs, where undimmed (sRGB 0.52 = 0.24) chose dark ones. Opaque, the surface
     *  still decides. */
    @Test fun theDimOverHomeUnderTheDrawerCountsInTheBackdrop() {
        val open = drawerScrimAlpha(1f)
        assertThat(drawerIsLightOverStatusBar(lightTheme, 0.05f, ownBarVisible = true, scrimOpacity = 0.5f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        assertThat(drawerIsLightOverStatusBar(lightTheme, 0.05f, ownBarVisible = true, scrimOpacity = 0.5f, wallpaperSupportsDarkText = true, homeDim = open)).isFalse()
        // The navigation bar: a dark theme at 40 % over the white wallpaper is light undimmed (0.35), dark dimmed (0.16).
        assertThat(drawerIsLightOverNavigationBar(darkTheme, 0.4f, wallpaperSupportsDarkText = true, homeDim = 0f)).isTrue()
        assertThat(drawerIsLightOverNavigationBar(darkTheme, 0.4f, wallpaperSupportsDarkText = true, homeDim = open)).isFalse()
        // Opaque: the dim is behind the drawer, the surface decides.
        assertThat(drawerIsLightOverStatusBar(lightTheme, 1f, ownBarVisible = true, scrimOpacity = 1f, wallpaperSupportsDarkText = false, homeDim = open)).isTrue()
        assertThat(drawerIsLightOverNavigationBar(darkTheme, 1f, wallpaperSupportsDarkText = true, homeDim = open)).isFalse()

        // The whole decision, as the shell wires it: the dim comes from the drawer's progress inside the snapshot.
        val progress = mutableFloatStateOf(1f)
        val dimsSeen = mutableListOf<Float>()
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { dim, _ ->
                dimsSeen += dim
                drawerIsLightOverStatusBar(lightTheme, 0.05f, ownBarVisible = true, scrimOpacity = 0.5f, wallpaperSupportsDarkText = true, homeDim = dim)
            },
            drawerIsLightOverNavBar = { dim, _ -> drawerIsLightOverNavigationBar(darkTheme, 0.4f, wallpaperSupportsDarkText = true, homeDim = dim) },
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

    /** The round-8 finding, and the owner's call that the bars' glyphs follow the same rule as the drawer's text: the
     *  shell's 32 % dim over a white wallpaper leaves sRGB 0.68 on screen = relative luminance 0.42, not the linear
     *  0.68 the old mix passed to the drawer; a black drawer at 40 % over it is 0.14 -- white glyphs and white text alike
     *  (5.6:1), where the linear input chose black (3.8:1). An opaque orange drawer (0.49) takes dark glyphs with its
     *  black labels; purple (0.17) light ones. */
    @Test fun theShellsDimAndTheGlyphsFollowWhatTheScreenShows() {
        val open = drawerScrimAlpha(1f)
        assertThat(dimmedUnderDrawer(1f, open)).isWithin(0.01f).of(0.42f)
        assertThat(dimmedUnderDrawer(0f, open)).isEqualTo(0f)
        assertThat(darkReadsBetter(shownLuminance(Color.Black, 0.4f, dimmedUnderDrawer(1f, open)))).isFalse() // the drawer's text rule on the shell's input
        assertThat(drawerIsLightOverNavigationBar(Color.Black, 0.4f, wallpaperSupportsDarkText = true, homeDim = open)).isFalse()
        assertThat(drawerSurfaceIsLight(Color(0xFFFFA726), opacity = 1f, behindLuminance = 0f)).isTrue()
        assertThat(drawerSurfaceIsLight(Color(0xFFAB47BC), opacity = 1f, behindLuminance = 0f)).isFalse()
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

    /** The glass layer (1.0.5) is a white tint over the whole wallpaper, behind the bars too. A strong one
     *  turns a dark wallpaper light behind the status bar, where dark glyphs then read better; a weak one
     *  leaves it dark; a light wallpaper stays light. The tint is weaker at the bottom: even the strongest
     *  setting leaves a dark wallpaper dark behind the navigation bar. */
    @Test fun theGlassLayerLightensWhatIsBehindTheBars() {
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = 0f)).isFalse()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = true, glassTint = 0f)).isTrue()
        val strong = glassTint(0.6f)
        val weak = glassTint(LauncherSettings.DEFAULT_GLASS_OPACITY)
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = strong.top)).isTrue()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = weak.top)).isFalse()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = true, glassTint = strong.top)).isTrue()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = strong.bottom)).isFalse()
        // What the screen shows: white at 81 % over black is sRGB 0.81, relative luminance 0.62.
        assertThat(wallpaperOnHomeLuminance(wallpaperSupportsDarkText = false, glassTint = 0.81f)).isWithin(0.02f).of(0.62f)
        assertThat(wallpaperOnHomeLuminance(wallpaperSupportsDarkText = false, glassTint = 0f)).isEqualTo(0f)
        assertThat(wallpaperOnHomeLuminance(wallpaperSupportsDarkText = true, glassTint = 0f)).isEqualTo(1f)
    }

    /** On home the glyphs follow the glass as much of it as is on screen: it is part of home, which fades
     *  out under the rising drawer, and the dark wallpaper is back behind the bar once it has. */
    @Test fun theGlyphsFollowTheGlassWhileHomeIsOnScreen() {
        val progress = mutableFloatStateOf(0f)
        val strong = glassTint(0.6f)
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { _, _ -> false },
            drawerIsLightOverNavBar = { _, _ -> false },
            coverIsLight = { null },
            ownBarBacksDark = { false },
            wallpaperSupportsDarkText = { false },
            glassTopTint = { strong.top },
            glassBottomTint = { strong.bottom },
        )
        assertThat(glyphs.darkStatus.value).isTrue()
        assertThat(glyphs.darkNav.value).isFalse()

        progress.floatValue = HOME_FADE_SPAN
        assertThat(glyphs.darkStatus.value).isFalse()
    }

    /** A wallpaper without the dark-text hint is not black. A plain mid grey (#707070, luminance 0.16) under the
     *  glass's bottom tint at 60 % (0.42) shows a light grey where black glyphs read at 9:1 and white ones at
     *  2.3:1; the hint alone, taken as black, kept the navigation glyphs white. Under the glass the
     *  wallpaper's own colour counts wherever the platform tells it. */
    @Test fun underTheGlassAMidToneWallpaperCountsAsWhatItIs() {
        val grey = 0xFF707070.toInt()
        val strong = glassTint(0.6f)
        // The colour not known: the hint, as before.
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = strong.bottom)).isFalse()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = strong.bottom, wallpaperColor = grey)).isTrue()
        // A black wallpaper stays dark under the same tint; a hinted one is light whatever its main colour.
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = strong.bottom, wallpaperColor = 0xFF000000.toInt())).isFalse()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = true, glassTint = strong.bottom, wallpaperColor = 0xFF050505.toInt())).isTrue()
        // Without the glass the hint alone decides, as it always has.
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = 0f, wallpaperColor = grey)).isFalse()
        assertThat(wallpaperOnHomeLuminance(wallpaperSupportsDarkText = false, glassTint = 0f, wallpaperColor = grey)).isEqualTo(0f)
        // What the screen shows: sRGB 0.44 under 42 % white is 0.67, relative luminance 0.41.
        assertThat(wallpaperOnHomeLuminance(wallpaperSupportsDarkText = false, glassTint = strong.bottom, wallpaperColor = grey))
            .isWithin(0.02f).of(0.41f)

        val glyphs = SystemBarGlyphs(
            progress = { 0f },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { _, _ -> false },
            drawerIsLightOverNavBar = { _, _ -> false },
            coverIsLight = { null },
            ownBarBacksDark = { false },
            wallpaperSupportsDarkText = { false },
            glassTopTint = { strong.top },
            glassBottomTint = { strong.bottom },
            wallpaperColor = { grey },
        )
        assertThat(glyphs.darkNav.value).isTrue()
        assertThat(glyphs.darkStatus.value).isTrue()
        // A see-through drawer over the navigation bar sees the same wallpaper under the glass.
        assertThat(drawerIsLightOverNavigationBar(darkTheme, opacity = 0f, wallpaperSupportsDarkText = false, homeDim = 0f, glassTint = strong.bottom, wallpaperColor = grey))
            .isTrue()
    }

    /** The glass is mixed over the wallpaper's own COLOUR, channel by channel, and the luminance taken from the
     *  result: a grey of the same luminance mixes lighter than a saturated colour does. A plain blue (#0000FF)
     *  under the glass's top tint at 20 % (0.27) is #4545FF, luminance 0.13, where white glyphs read at 5.9:1
     *  and black at 3.5:1; mixed as a grey it came out at 0.20 and turned the glyphs black. */
    @Test fun theGlassIsMixedOverTheWallpapersColourNotAGreyOfIt() {
        val blue = 0xFF0000FF.toInt()
        val top = glassTint(0.2f).top
        assertThat(wallpaperOnHomeLuminance(wallpaperSupportsDarkText = false, glassTint = top, wallpaperColor = blue))
            .isWithin(0.01f).of(0.127f)
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = top, wallpaperColor = blue)).isFalse()
        // Light glyphs keep their dark gradient behind them.
        val darkGlyphs = homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = top, wallpaperColor = blue)
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, statusBarPx = 130f, headsUpActive = false, wallpaperSupportsDarkText = darkGlyphs, covered = false))
            .isTrue()
        // A yellow of the same hue-free luminance class goes the other way: light under the same glass.
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = top, wallpaperColor = 0xFFFFEE58.toInt())).isTrue()
        // The own bar's scrim is mixed over the result the same way: 40 % black over #4545FF is #292999.
        assertThat(behindStatusBarLuminance(ownBarVisible = true, scrimOpacity = 0.4f, wallpaperSupportsDarkText = false, glassTint = top, wallpaperColor = blue))
            .isWithin(0.01f).of(0.044f)
    }

    /** A see-through drawer with a colour of its own is mixed over the glassed wallpaper's COLOUR too, layer by
     *  layer: a blue drawer at 40 % over a red wallpaper under the glass at 20 % (bottom tint 0.14), home dimmed
     *  10 %, shows a dull violet of luminance 0.16 where light glyphs read better; mixed over a grey as bright as
     *  that backdrop it came out at 0.23 and chose dark ones. Without the glass nothing changes. */
    @Test fun aColouredSeeThroughDrawerIsMixedOverTheGlassedWallpapersColour() {
        val blueDrawer = Color(0xFF42A5F5)
        val red = 0xFFFF0000.toInt()
        val bottom = glassTint(0.2f).bottom
        assertThat(drawerIsLightOverNavigationBar(blueDrawer, opacity = 0.4f, wallpaperSupportsDarkText = false, homeDim = 0.1f, glassTint = bottom, wallpaperColor = red))
            .isFalse()
        // The status bar's side of the same: no own bar, the glass's top tint.
        assertThat(
            drawerIsLightOverStatusBar(
                blueDrawer, opacity = 0.4f, ownBarVisible = false, scrimOpacity = 0f, wallpaperSupportsDarkText = false, homeDim = 0.1f,
                glassTint = bottom, wallpaperColor = red,
            ),
        ).isFalse()
        // The layers one by one: 14 % white over red, 10 % black over that, 40 % of the drawer's blue over that.
        assertThat(drawerSurfaceIsLightOverColor(blueDrawer, opacity = 0.4f, behind = 0xFFFF2424.toInt(), homeDim = 0.1f)).isFalse()
        // An opaque drawer is its own colour whatever is behind (the offered blue reads with dark glyphs).
        assertThat(drawerIsLightOverNavigationBar(blueDrawer, opacity = 1f, wallpaperSupportsDarkText = false, homeDim = 0.1f, glassTint = bottom, wallpaperColor = red))
            .isTrue()
        // Without the glass: the hint as black, as before.
        assertThat(drawerIsLightOverNavigationBar(blueDrawer, opacity = 0.4f, wallpaperSupportsDarkText = false, homeDim = 0.1f)).isFalse()
    }

    /** An open folder lays a 50 % black scrim over the whole of home, the bars' strips included. Black wallpaper
     *  and the glass at 40 % are light enough behind the status bar for dark glyphs; under the folder's scrim
     *  they are a dark grey again (white glyphs 9.7:1, black 2.2:1). */
    @Test fun anOpenFoldersScrimDarkensWhatIsBehindTheBars() {
        val black = 0xFF000000.toInt()
        val top = glassTint(0.4f).top
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = top, wallpaperColor = black)).isTrue()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = top, wallpaperColor = black, homeScrim = 0.5f)).isFalse()

        val scrim = mutableFloatStateOf(0f)
        val glyphs = SystemBarGlyphs(
            progress = { 0f },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { _, _ -> false },
            drawerIsLightOverNavBar = { _, _ -> false },
            coverIsLight = { null },
            ownBarBacksDark = { false },
            wallpaperSupportsDarkText = { false },
            glassTopTint = { top },
            glassBottomTint = { glassTint(0.4f).bottom },
            wallpaperColor = { black },
            homeScrim = { scrim.floatValue },
        )
        assertThat(glyphs.darkStatus.value).isTrue()
        // The folder opens: its scrim is state, the glyphs follow it.
        scrim.floatValue = 0.5f
        assertThat(glyphs.darkStatus.value).isFalse()
        assertThat(glyphs.darkNav.value).isFalse()
    }

    /** The shell dims the whole screen as the drawer rises, the status bar's strip too while the drawer is
     *  still below it. A mid-grey wallpaper (#757575) with the glass at 20 %, the drawer held 55 % open: what
     *  is left of the glass alone would ask for dark glyphs, but under the 17.6 % dim the strip is darker
     *  (white 6.0:1, black 3.5:1). */
    @Test fun theShellsDimCountsBehindTheBareStatusBarToo() {
        val grey = 0xFF757575.toInt()
        val top = glassTint(0.2f).top
        val leftOfTheGlass = top * homeContentAlpha(0.55f)
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = leftOfTheGlass, wallpaperColor = grey)).isTrue()
        assertThat(
            homeBackdropSupportsDarkGlyphs(
                wallpaperSupportsDarkText = false, glassTint = leftOfTheGlass, wallpaperColor = grey, shellDim = drawerScrimAlpha(0.55f),
            ),
        ).isFalse()

        val progress = mutableFloatStateOf(0.55f)
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { _, _ -> false },
            drawerIsLightOverNavBar = { _, _ -> false },
            coverIsLight = { null },
            ownBarBacksDark = { false },
            wallpaperSupportsDarkText = { false },
            glassTopTint = { top },
            glassBottomTint = { glassTint(0.2f).bottom },
            wallpaperColor = { grey },
        )
        assertThat(glyphs.darkStatus.value).isFalse()
    }

    /** The dark gradient behind light glyphs follows the glyphs as they are NOW. A light grey wallpaper without
     *  the hint (#B0B0B0) under the glass at 20 % takes dark glyphs and no gradient at rest; with the drawer
     *  held 65 % open the glass has faded with home, the glyphs are light again and the gradient is back
     *  behind them; it goes once the drawer covers the bar, as always. */
    @Test fun theTopScrimComesBackWhenTheGlyphsTurnLight() {
        val progress = mutableFloatStateOf(0f)
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { _, _ -> false },
            drawerIsLightOverNavBar = { _, _ -> false },
            coverIsLight = { null },
            ownBarBacksDark = { false },
            wallpaperSupportsDarkText = { false },
            glassTopTint = { glassTint(0.2f).top },
            glassBottomTint = { glassTint(0.2f).bottom },
            wallpaperColor = { 0xFFB0B0B0.toInt() },
        )
        fun scrim() = homeTopScrimAlpha(progress.floatValue, heightPx = 2400f, barPx = 130f, lightGlyphs = !glyphs.darkStatus.value)
        assertThat(glyphs.darkStatus.value).isTrue()
        assertThat(scrim()).isEqualTo(0f)

        progress.floatValue = 0.65f
        assertThat(glyphs.darkStatus.value).isFalse()
        assertThat(scrim()).isEqualTo(HOME_TOP_SCRIM_ALPHA)

        progress.floatValue = 1f
        assertThat(scrim()).isEqualTo(0f)
        // Light glyphs by default: the callers that know no better keep the gradient they had.
        assertThat(homeTopScrimAlpha(0f, heightPx = 2400f, barPx = 130f)).isEqualTo(HOME_TOP_SCRIM_ALPHA)
    }

    /** ARK's own bar with a weak scrim (under the 50 % that counts as dark backing) leaves the decision to what
     *  is behind it, and that is the glass UNDER the scrim: black wallpaper, glass at 40 % and a 40 % scrim
     *  show a dark grey where white glyphs read at 7.7:1 and black ones at 2.7:1. The glass alone would have
     *  chosen black. */
    @Test fun aWeakOwnBarScrimStillDimsTheGlassBehindTheGlyphs() {
        val top = glassTint(0.4f).top
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = top)).isTrue()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = top, ownBarScrim = 0.4f)).isFalse()
        // A light wallpaper under the same weak scrim stays light, with the glass or without it.
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = true, glassTint = top, ownBarScrim = 0.4f)).isTrue()
        assertThat(homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = true, glassTint = 0f, ownBarScrim = 0.4f)).isTrue()

        val progress = mutableFloatStateOf(0f)
        val scrim = mutableFloatStateOf(0.4f)
        val glyphs = SystemBarGlyphs(
            progress = { progress.floatValue },
            heightPx = { 2400f },
            statusBarPx = { 130f },
            navBarPx = { 60f },
            drawerIsLightOverStatusBar = { _, _ -> false },
            drawerIsLightOverNavBar = { _, _ -> false },
            coverIsLight = { null },
            ownBarBacksDark = { homeAlpha -> ownBarBacksDark(ownBarShown = true, scrimOpacity = ownBarScrimOnScreen(scrim.floatValue, homeAlpha)) },
            wallpaperSupportsDarkText = { false },
            glassTopTint = { top },
            glassBottomTint = { 0f },
            ownBarScrim = { homeAlpha -> ownBarScrimOnScreen(scrim.floatValue, homeAlpha) },
        )
        assertThat(glyphs.darkStatus.value).isFalse()
        // Without the scrim the glass decides: dark glyphs.
        scrim.floatValue = 0f
        assertThat(glyphs.darkStatus.value).isTrue()
    }

    /** ARK's own bar draws its scrim over the glass: the scrim dims the lightened wallpaper as it dims a
     *  light one, and where the glass makes the glyphs dark there is no dark gradient behind them. */
    @Test fun theOwnBarsScrimAndTheTopScrimCountTheGlass() {
        assertThat(behindStatusBarLuminance(ownBarVisible = false, scrimOpacity = 0.6f, wallpaperSupportsDarkText = false, glassTint = 0.81f))
            .isWithin(0.02f).of(0.62f)
        assertThat(behindStatusBarLuminance(ownBarVisible = true, scrimOpacity = 0.6f, wallpaperSupportsDarkText = false, glassTint = 0.81f))
            .isLessThan(0.12f)
        // A see-through drawer over the navigation bar: the glass under it lightens a dark wallpaper there too.
        assertThat(drawerIsLightOverNavigationBar(darkTheme, opacity = 0f, wallpaperSupportsDarkText = false, homeDim = 0f, glassTint = 0.81f)).isTrue()
        assertThat(drawerIsLightOverNavigationBar(darkTheme, opacity = 0f, wallpaperSupportsDarkText = false, homeDim = 0f)).isFalse()

        val darkGlyphs = homeBackdropSupportsDarkGlyphs(wallpaperSupportsDarkText = false, glassTint = 0.81f)
        assertThat(homeTopScrimShown(ownBarShown = false, systemBarHidden = false, statusBarPx = 130f, headsUpActive = false, wallpaperSupportsDarkText = darkGlyphs, covered = false))
            .isFalse()
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
            drawerIsLightOverStatusBar = { _, _ -> true },
            drawerIsLightOverNavBar = { _, _ -> true },
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
        val firstRead = CompletableDeferred<WallpaperLook>()
        val reading = launch(start = CoroutineStart.UNDISPATCHED) { wallpaper.readInitial { firstRead.await() } }

        wallpaper.onColorsChanged(WallpaperLook(supportsDarkText = true, color = 0xFFEEEEEE.toInt()))
        firstRead.complete(WallpaperLook(supportsDarkText = false, color = 0xFF101010.toInt()))
        reading.join()

        assertThat(wallpaper.state.value).isTrue()
        // The main colour travels with the hint: the newer report's, not the stale read's.
        assertThat(wallpaper.color.value).isEqualTo(0xFFEEEEEE.toInt())
    }

    @Test fun theFirstReadLandsWhenNothingNewerCame() = runBlocking {
        val wallpaper = WallpaperDarkText()

        assertThat(wallpaper.color.value).isNull()
        wallpaper.readInitial { WallpaperLook(supportsDarkText = true, color = 0xFFF0F0F0.toInt()) }

        assertThat(wallpaper.state.value).isTrue()
        assertThat(wallpaper.color.value).isEqualTo(0xFFF0F0F0.toInt())
        // A wallpaper that tells no colours: no hint and no colour.
        wallpaper.onColorsChanged(WallpaperLook(supportsDarkText = false))
        assertThat(wallpaper.state.value).isFalse()
        assertThat(wallpaper.color.value).isNull()
    }

    @Test fun theWallpaperHintIsRead() {
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_TEXT)).isTrue()
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_TEXT or WallpaperColors.HINT_SUPPORTS_DARK_THEME)).isTrue()
        assertThat(supportsDarkText(WallpaperColors.HINT_SUPPORTS_DARK_THEME)).isFalse()
        assertThat(supportsDarkText(0)).isFalse()
    }
}
