package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.smartspace.CurrentWeather
import org.arkikeskus.launcher.data.smartspace.WeatherKind
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.argbLuminance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The clock/weather widget's own look (the owner's 1.0.5 notes): a background colour at an opacity
 * of its choice, a text-size slider, and the weather icon in more than the one emoji: larger, drawn
 * in colour, or drawn and moving. Out of the box nothing changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real font metrics: the text-size test measures the clock
class SmartspaceStyleTest {
    @get:Rule val compose = createComposeRule()

    private val weatherApp = AppItem("com.weather", "com.weather.Main", Process.myUserHandle(), 0L, "Weather")
    private val weather = CurrentWeather(temperatureC = 12.4, weatherCode = 61, city = "Oulu", fetchedAt = 0L)
    private var opened = 0
    private var style by mutableStateOf(SmartspaceStyle())

    private fun show(height: Int) {
        val state = SmartspaceState(
            hasCalendarPermission = true, nextEvent = null, hasLocationPermission = true,
            showWeather = true, weather = weather, weatherApp = weatherApp,
        )
        compose.setContent {
            CompositionLocalProvider(LocalSmartspaceStyle provides style) {
                Box(Modifier.size(360.dp, height.dp)) {
                    SmartspaceContent(state, onRefresh = {}, onOpenWeatherApp = { opened++ }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun glyphs() = compose.onAllNodesWithTag(WEATHER_GLYPH_TAG, useUnmergedTree = true).fetchSemanticsNodes()

    @Test fun theStandardIconIsTheEmojiInTheText() {
        show(height = 280)

        assertThat(glyphs()).isEmpty()
        compose.onNode(hasText("12° 🌧️ Oulu")).assertExists()
    }

    @Test fun everyOtherStyleShowsAnIconOfItsOwnAndTheLineStillOpensTheWeatherApp() {
        for (icon in listOf(LauncherSettings.WEATHER_ICON_LARGE, LauncherSettings.WEATHER_ICON_COLOR, LauncherSettings.WEATHER_ICON_ANIMATED)) {
            style = SmartspaceStyle(weatherIcon = icon)
            if (icon == LauncherSettings.WEATHER_ICON_LARGE) show(height = 280) else compose.waitForIdle()

            assertThat(glyphs()).hasSize(1)
            compose.onNode(hasText("12°", substring = true)).assertHasClickAction().performClick()
        }
        assertThat(opened).isEqualTo(3)
        // The place still shows beside it.
        compose.onNode(hasText("Oulu", substring = true)).assertExists()
    }

    @Test fun theIconShowsInTheCompactAndTheShortLayoutToo() {
        style = SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_COLOR)
        show(height = 120)

        assertThat(glyphs()).hasSize(1)
    }

    @Test fun theTextSizeSettingGrowsTheClock() {
        show(height = 280)
        val normal = compose.onNodeWithTag(SMARTSPACE_CLOCK_TAG, useUnmergedTree = true).fetchSemanticsNode().size.height

        style = SmartspaceStyle(textScale = 1.4f)
        compose.waitForIdle()

        assertThat(compose.onNodeWithTag(SMARTSPACE_CLOCK_TAG, useUnmergedTree = true).fetchSemanticsNode().size.height).isGreaterThan(normal)
    }

    /** No colour chosen: the card is the dark veil (or the theme's surface) it was; a colour is painted
     *  at the opacity, and the text turns dark on a light, solid enough card. */
    @Test fun theCardColourAndItsInk() {
        val veil = Color.Black.copy(alpha = 0.3f)
        assertThat(smartspaceSurface(SmartspaceStyle(), default = veil, tonal = false)).isEqualTo(veil)
        assertThat(smartspaceSurface(SmartspaceStyle(backgroundOpacity = 0.6f), default = veil, tonal = false))
            .isEqualTo(Color.Black.copy(alpha = 0.6f))
        // The theme's own surface (the tonal setting) stays as it is without a colour of the user's.
        assertThat(smartspaceSurface(SmartspaceStyle(backgroundOpacity = 0.6f), default = Color.Gray, tonal = true)).isEqualTo(Color.Gray)
        val yellow = 0xFFFFEE58.toInt()
        assertThat(smartspaceSurface(SmartspaceStyle(backgroundColor = yellow, backgroundOpacity = 0.5f), default = veil, tonal = true))
            .isEqualTo(Color(yellow).copy(alpha = 0.5f))

        val blackWallpaper = 0xFF000000.toInt()
        val whiteWallpaper = 0xFFFFFFFF.toInt()
        assertThat(smartspaceInk(SmartspaceStyle(), default = Color.White, wallpaper = whiteWallpaper)).isEqualTo(Color.White)
        assertThat(smartspaceInk(SmartspaceStyle(backgroundColor = yellow, backgroundOpacity = 0.9f), Color.White, blackWallpaper)).isEqualTo(Color.Black)
        assertThat(smartspaceInk(SmartspaceStyle(backgroundColor = yellow, backgroundOpacity = 0.2f), Color.White, blackWallpaper)).isEqualTo(Color.White)
        assertThat(smartspaceInk(SmartspaceStyle(backgroundColor = 0xFF000000.toInt(), backgroundOpacity = 1f), Color.Black, whiteWallpaper)).isEqualTo(Color.White)
        // The offered blue, solid: black reads far better on it than white (7.9:1 against 2.65:1).
        assertThat(smartspaceInk(SmartspaceStyle(backgroundColor = 0xFF42A5F5.toInt(), backgroundOpacity = 1f), Color.White, blackWallpaper)).isEqualTo(Color.Black)
        // A faint white card goes by the wallpaper behind it: light text over a black one, dark over a white one.
        val faintWhite = SmartspaceStyle(backgroundColor = 0xFFFFFFFF.toInt(), backgroundOpacity = 0.3f)
        assertThat(smartspaceInk(faintWhite, Color.White, blackWallpaper)).isEqualTo(Color.White)
        assertThat(smartspaceInk(faintWhite, Color.White, whiteWallpaper)).isEqualTo(Color.Black)
    }

    /** A drawn icon says what weather it shows: a screen reader hears it as it heard the emoji. */
    @Test fun theDrawnIconNamesTheWeather() {
        style = SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_COLOR)
        show(height = 280)

        compose.onNodeWithContentDescription("Rain", useUnmergedTree = true).assertExists()
        // The whole line, as a screen reader takes it: the temperature, the weather and the place.
        compose.onNode(hasText("12°", substring = true) and hasContentDescription("Rain")).assertExists()
    }

    @Test fun everyKindOfWeatherHasAName() {
        val names = WeatherKind.entries.map { RuntimeEnvironment.getApplication().getString(weatherKindName(it)) }
        assertThat(names.toSet()).hasSize(WeatherKind.entries.size)
    }

    /** The moving icon moves for a while after the home screen comes back, then rests (battery). */
    @Test fun theAnimationRunsForABoundedTime() {
        assertThat(weatherAnimationRunning(elapsedMs = 0, animated = true, systemAnimations = true)).isTrue()
        assertThat(weatherAnimationRunning(elapsedMs = WEATHER_ANIMATION_MS - 1, animated = true, systemAnimations = true)).isTrue()
        assertThat(weatherAnimationRunning(elapsedMs = WEATHER_ANIMATION_MS, animated = true, systemAnimations = true)).isFalse()
        assertThat(weatherAnimationRunning(elapsedMs = 0, animated = false, systemAnimations = true)).isFalse()
        // "Remove animations" in the system's accessibility settings stills it.
        assertThat(weatherAnimationRunning(elapsedMs = 0, animated = true, systemAnimations = false)).isFalse()
    }

    /** What is behind the home screen's cards is the wallpaper, and the glass panel over it when it is on:
     *  a strong panel over a black wallpaper is a light backdrop, and a faint white card on it needs dark text. */
    @Test fun theGlassPanelIsPartOfWhatIsBehindACard() {
        val black = 0xFF000000.toInt()
        assertThat(homeBackdropColor(black, glassPanel = false, glassOpacity = 0.6f)).isEqualTo(black)
        val behindGlass = homeBackdropColor(black, glassPanel = true, glassOpacity = 0.6f)
        assertThat(argbLuminance(behindGlass)).isGreaterThan(0.25f)

        val faintWhite = SmartspaceStyle(backgroundColor = 0xFFFFFFFF.toInt(), backgroundOpacity = 0.3f)
        assertThat(smartspaceInk(faintWhite, Color.White, wallpaper = black)).isEqualTo(Color.White)
        assertThat(smartspaceInk(faintWhite, Color.White, wallpaper = behindGlass)).isEqualTo(Color.Black)
    }

    /** The first read of the wallpaper's colour is slow (off the main thread): a colour the system
     *  announced meanwhile is newer and must not be overwritten by it. */
    @Test fun aLateFirstReadNeverReplacesANewerAnnouncedColour() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val state = WallpaperColorState()
        assertThat(state.color).isEqualTo(WALLPAPER_COLOR_UNKNOWN)
        state.onFirstRead(white)
        assertThat(state.color).isEqualTo(white)

        val raced = WallpaperColorState()
        raced.onAnnounced(black)
        raced.onFirstRead(white)
        assertThat(raced.color).isEqualTo(black)
        // An announcement without colours is still the newer word: unknown, not the stale read.
        val cleared = WallpaperColorState()
        cleared.onAnnounced(null)
        cleared.onFirstRead(white)
        assertThat(cleared.color).isEqualTo(WALLPAPER_COLOR_UNKNOWN)
        // A read that found nothing changes nothing.
        val empty = WallpaperColorState()
        empty.onFirstRead(null)
        assertThat(empty.color).isEqualTo(WALLPAPER_COLOR_UNKNOWN)
    }

    /** The glass panel's sheen: brighter at the top than the bottom, both following the strength. */
    @Test fun theGlassPanelFollowsItsStrength() {
        val soft = glassPanelAlphas(0.1f)
        val strong = glassPanelAlphas(0.5f)
        assertThat(soft.top).isGreaterThan(soft.bottom)
        assertThat(strong.top).isGreaterThan(soft.top)
        assertThat(strong.bottom).isGreaterThan(soft.bottom)
        assertThat(glassPanelAlphas(5f).top).isAtMost(1f)
    }
}
