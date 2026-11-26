package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.WallpaperLook
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
    private var weather = CurrentWeather(temperatureC = 12.4, weatherCode = 61, city = "Oulu", fetchedAt = 0L)
    /** The region's unit as the widget is told it (Celsius here, whatever the test machine's region); null = as
     *  the phone has it. */
    private var fahrenheit: Boolean? = false
    private var opened = 0
    private var style by mutableStateOf(SmartspaceStyle())

    private fun show(height: Int) {
        val state = SmartspaceState(
            hasCalendarPermission = true, nextEvent = null, hasLocationPermission = true,
            showWeather = true, weather = weather, weatherApp = weatherApp,
        )
        compose.setContent {
            // The home screen as it is under the app drawer: the moving icon at rest. It moves for as long as
            // it is shown, and a test that waits for the screen to settle would wait for good.
            CompositionLocalProvider(
                LocalSmartspaceStyle provides style, LocalWeatherInFahrenheit provides fahrenheit, LocalHomeScreenShown provides false,
            ) {
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
        val looks = listOf(
            SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_EMOJI, weatherIconSize = LauncherSettings.WEATHER_SIZE_LARGE),
            SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_COLOR),
            SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_ANIMATED),
            SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_COLOR, weatherIconSize = LauncherSettings.WEATHER_SIZE_LARGE),
            SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_ANIMATED, weatherIconSize = LauncherSettings.WEATHER_SIZE_LARGE),
        )
        looks.forEachIndexed { i, look ->
            style = look
            if (i == 0) show(height = 280) else compose.waitForIdle()

            assertThat(glyphs()).hasSize(1)
            compose.onNode(hasText("12°", substring = true)).assertHasClickAction().performClick()
        }
        assertThat(opened).isEqualTo(looks.size)
        // The place still shows beside it.
        compose.onNode(hasText("Oulu", substring = true)).assertExists()
    }

    /** The size is a choice of its own beside the style (the owner's note: animated AND large at once):
     *  the drawn icon, still or moving, is larger with it, and the emoji leaves the text for an icon of
     *  its own; at the standard size the emoji is the text it always was. */
    @Test fun theSizeSettingAppliesToEveryStyle() {
        style = SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_COLOR)
        show(height = 280)
        val standard = glyphs().single().size.height

        for (icon in listOf(LauncherSettings.WEATHER_ICON_COLOR, LauncherSettings.WEATHER_ICON_ANIMATED)) {
            style = SmartspaceStyle(weatherIcon = icon, weatherIconSize = LauncherSettings.WEATHER_SIZE_LARGE)
            compose.waitForIdle()
            assertThat(glyphs().single().size.height).isGreaterThan(standard)
        }
        style = SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_ANIMATED)
        compose.waitForIdle()
        assertThat(glyphs().single().size.height).isEqualTo(standard)

        style = SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_EMOJI, weatherIconSize = LauncherSettings.WEATHER_SIZE_LARGE)
        compose.waitForIdle()
        assertThat(glyphs()).hasSize(1)
        style = SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_EMOJI)
        compose.waitForIdle()
        assertThat(glyphs()).isEmpty()
    }

    @Test fun theSettingsCarryTheStyleAndTheSize() {
        assertThat(LauncherSettings().smartspaceStyle()).isEqualTo(SmartspaceStyle())
        assertThat(LauncherSettings(weatherIconStyle = LauncherSettings.WEATHER_ICON_ANIMATED, weatherIconSize = LauncherSettings.WEATHER_SIZE_LARGE).smartspaceStyle())
            .isEqualTo(SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_ANIMATED, weatherIconSize = LauncherSettings.WEATHER_SIZE_LARGE))
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

    /** Without a colour of the user's the card is the dark veil. Exactly as it has always been (30 %) it
     *  keeps the white text it has always had; at an opacity of the user's the text goes by contrast like
     *  any other card: a veil turned down to nothing over a white wallpaper needs dark text. The theme's
     *  own solid surface (the tonal setting) keeps the theme's text. */
    @Test fun theVeilAtAnOpacityOfTheUsersGoesByContrastToo() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertThat(smartspaceInk(SmartspaceStyle(backgroundOpacity = 0f), Color.White, wallpaper = white)).isEqualTo(Color.Black)
        assertThat(smartspaceInk(SmartspaceStyle(backgroundOpacity = 0f), Color.White, wallpaper = black)).isEqualTo(Color.White)
        // A strong veil over a white wallpaper is dark again.
        assertThat(smartspaceInk(SmartspaceStyle(backgroundOpacity = 0.7f), Color.White, wallpaper = white)).isEqualTo(Color.White)
        // The veil as it ships: white, whatever the wallpaper (the slider's own step lands on it too).
        assertThat(smartspaceInk(SmartspaceStyle(), Color.White, wallpaper = white)).isEqualTo(Color.White)
        assertThat(smartspaceInk(SmartspaceStyle(backgroundOpacity = 0.3f + 0.0001f), Color.White, wallpaper = white)).isEqualTo(Color.White)
        val onSurface = Color(0xFF1A1C1E)
        assertThat(smartspaceInk(SmartspaceStyle(backgroundOpacity = 0f), onSurface, wallpaper = black, tonal = true)).isEqualTo(onSurface)
    }

    /** The short widget draws its texts without a shadow: with the veil turned down to nothing over a
     *  white wallpaper they must not stay white on white. Read from the clock's own pixels. */
    @Test fun theShortWidgetStaysReadableWithoutItsVeilOnAWhiteWallpaper() {
        style = SmartspaceStyle(backgroundOpacity = 0f)
        val state = SmartspaceState(
            hasCalendarPermission = true, nextEvent = null, hasLocationPermission = true,
            showWeather = true, weather = weather, weatherApp = weatherApp,
        )
        compose.setContent {
            val backdrop = remember { HomeBackdrop(wallpaper = 0xFFFFFFFF.toInt()) }
            CompositionLocalProvider(LocalSmartspaceStyle provides style, LocalHomeBackdrop provides backdrop) {
                Box(Modifier.size(360.dp, 120.dp).background(Color.White)) {
                    SmartspaceContent(state, onRefresh = {}, onOpenWeatherApp = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()

        val pixels = compose.onNodeWithTag(SMARTSPACE_CLOCK_TAG, useUnmergedTree = true).captureToImage().toPixelMap()
        var darkest = 1f
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) darkest = minOf(darkest, pixels[x, y].luminance())
        assertThat(darkest).isLessThan(0.3f)
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

    /** The moving icon moves for as long as it is shown. */
    @Test fun theMovingIconKeepsMovingWhileItIsShown() {
        // The owner's word, 2026-11-19: the moving icon keeps moving. It moved for eight seconds after the
        // home screen came back and then rested, which read as a fault.
        assertThat(weatherAnimationRunning(animated = true, systemAnimations = true, shown = true)).isTrue()
        // Not where nobody sees it: behind another app, behind the app drawer, with the screen off.
        assertThat(weatherAnimationRunning(animated = true, systemAnimations = true, shown = false)).isFalse()
        assertThat(weatherAnimationRunning(animated = false, systemAnimations = true, shown = true)).isFalse()
        // The system's "Remove animations" stills it altogether.
        assertThat(weatherAnimationRunning(animated = true, systemAnimations = false, shown = true)).isFalse()
    }

    /** What is behind the home screen's cards is the wallpaper, and the glass panel over it when it is on:
     *  a strong panel over a black wallpaper is a light backdrop, and a faint white card on it needs dark text. */
    @Test fun theGlassPanelIsPartOfWhatIsBehindACard() {
        val black = 0xFF000000.toInt()
        assertThat(homeBackdropColor(black, glassOpacity = null)).isEqualTo(black)
        val behindGlass = homeBackdropColor(black, glassOpacity = 0.6f)
        assertThat(argbLuminance(behindGlass)).isGreaterThan(0.25f)

        val faintWhite = SmartspaceStyle(backgroundColor = 0xFFFFFFFF.toInt(), backgroundOpacity = 0.3f)
        assertThat(smartspaceInk(faintWhite, Color.White, wallpaper = black)).isEqualTo(Color.White)
        assertThat(smartspaceInk(faintWhite, Color.White, wallpaper = behindGlass)).isEqualTo(Color.Black)
    }

    /** The panel fades from its top to its bottom, so what is behind a card depends on how far down the
     *  panel it sits: over a black wallpaper at strength 45 %, a see-through widget near the top is on a
     *  light tint (dark text), one near the bottom on a dark one (light text, 7:1 against black's 2.9:1). */
    @Test fun theBackdropGoesByHowFarDownThePanelACardSits() {
        val black = 0xFF000000.toInt()
        val high = homeBackdropColor(black, glassOpacity = 0.45f, fraction = 0.1f)
        val middle = homeBackdropColor(black, glassOpacity = 0.45f)
        val low = homeBackdropColor(black, glassOpacity = 0.45f, fraction = 0.9f)
        assertThat(argbLuminance(high)).isGreaterThan(argbLuminance(middle))
        assertThat(argbLuminance(middle)).isGreaterThan(argbLuminance(low))

        val clear = SmartspaceStyle(backgroundColor = 0xFFFFFFFF.toInt(), backgroundOpacity = 0f)
        assertThat(smartspaceInk(clear, Color.White, wallpaper = high)).isEqualTo(Color.Black)
        assertThat(smartspaceInk(clear, Color.White, wallpaper = low)).isEqualTo(Color.White)
    }

    /** Where a point is on the panel, as a share of its height in steps of a twentieth (a card that
     *  moves a little does not re-pick its colours); the middle while the panel is not laid out. */
    @Test fun thePanelsBoundsPlaceACard() {
        val bounds = GlassPanelBounds()
        assertThat(bounds.fractionOf(700f)).isEqualTo(0.5f)
        bounds.top = 100f
        bounds.height = 1000f
        assertThat(bounds.fractionOf(100f)).isEqualTo(0f)
        assertThat(bounds.fractionOf(600f)).isEqualTo(0.5f)
        assertThat(bounds.fractionOf(1000f)).isWithin(0.001f).of(0.9f)
        assertThat(bounds.fractionOf(612f)).isEqualTo(0.5f)
        assertThat(bounds.fractionOf(-50f)).isEqualTo(0f)
        assertThat(bounds.fractionOf(5000f)).isEqualTo(1f)
    }

    /** The widget itself: the same see-through widget draws dark text high on the panel and light
     *  text low on it. Read from the clock's own pixels over a grey ground: light text leaves
     *  near-white pixels, dark text none brighter than the ground. */
    @Config(qualifiers = "+w400dp-h900dp") // room for the widget low on an 800 dp panel
    @Test fun theWidgetPicksItsTextByWhereItSitsOnThePanel() {
        style = SmartspaceStyle(backgroundColor = 0xFFFFFFFF.toInt(), backgroundOpacity = 0f)
        var top by mutableStateOf(0)
        val state = SmartspaceState(
            hasCalendarPermission = true, nextEvent = null, hasLocationPermission = true,
            showWeather = true, weather = weather, weatherApp = weatherApp,
        )
        compose.setContent {
            val panel = remember { GlassPanelBounds() }
            val backdrop = remember { HomeBackdrop(wallpaper = 0xFF000000.toInt(), glassOpacity = 0.45f, panel = panel) }
            CompositionLocalProvider(LocalSmartspaceStyle provides style, LocalHomeBackdrop provides backdrop) {
                Box(
                    Modifier
                        .size(360.dp, 800.dp)
                        .background(Color(0xFF777777))
                        .onGloballyPositioned { panel.top = it.positionInRoot().y; panel.height = it.size.height.toFloat() },
                ) {
                    Box(Modifier.padding(top = top.dp).size(360.dp, 200.dp)) {
                        SmartspaceContent(state, onRefresh = {}, onOpenWeatherApp = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
        assertThat(brightestClockPixel()).isLessThan(0.6f)

        top = 600
        compose.waitForIdle()
        assertThat(brightestClockPixel()).isGreaterThan(0.85f)
    }

    /** The luminance of the brightest pixel in the clock's bounds. */
    private fun brightestClockPixel(): Float {
        val pixels = compose.onNodeWithTag(SMARTSPACE_CLOCK_TAG, useUnmergedTree = true).captureToImage().toPixelMap()
        var brightest = 0f
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) brightest = maxOf(brightest, pixels[x, y].luminance())
        return brightest
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

    /** Every motion's period divides eight seconds, so the drawing's clock can start over there without a jump:
     *  it never grows without end however long the home screen stays up. */
    @Test fun theMovingIconsClockStartsOverEveryEightSeconds() {
        assertThat(weatherPhaseSeconds(0)).isEqualTo(0f)
        assertThat(weatherPhaseSeconds(2_500)).isEqualTo(2.5f)
        assertThat(weatherPhaseSeconds(8_000)).isEqualTo(0f)
        assertThat(weatherPhaseSeconds(3_600_000L + 1_000)).isEqualTo(1f)
    }

    /**
     * It is drawn thirty times a second at most: weather moves slowly, and every frame is the battery's. Review
     * round 3: it asked the display for EVERY frame and threw the early ones away after waking for them, 120
     * wake-ups a second on a 120 Hz display for 30 drawings. It waits first and asks for a frame only when it
     * will draw one.
     */
    @Test fun theMovingIconAsksForAFrameOnlyWhenItWillDrawOne() = runTest {
        // A display of 120 frames a second, and a clock the test moves (in microseconds: whole numbers, so
        // the next frame is always later than now).
        val frameUs = 8_333L
        var clockUs = 0L
        var framesAsked = 0
        val drawn = mutableListOf<Long>()

        val job = launch {
            weatherFrames(
                awaitFrame = { framesAsked++; clockUs = (clockUs / frameUs + 1) * frameUs; clockUs / 1_000 },
                wait = { clockUs += it * 1_000 },
                draw = { drawn += it; if (clockUs >= 1_000_000) throw kotlinx.coroutines.CancellationException("a second has passed") },
            )
        }
        job.join()

        // A second of it: about thirty drawings, and one frame asked for each (the first starts the clock).
        assertThat(drawn.size).isAtLeast(25)
        assertThat(drawn.size).isAtMost(31)
        assertThat(framesAsked).isEqualTo(drawn.size + 1)
    }

    /** The owner's word, 2026-11-19: Fahrenheit where the phone's region uses it, as its other apps show it. */
    @Test fun theTemperatureIsShownInTheRegionsUnit() {
        assertThat(shownTemperature(celsius = 22.4, fahrenheit = false)).isEqualTo(22)
        assertThat(shownTemperature(celsius = 22.4, fahrenheit = true)).isEqualTo(72)
        assertThat(shownTemperature(celsius = -0.4, fahrenheit = false)).isEqualTo(0)
        assertThat(shownTemperature(celsius = -40.0, fahrenheit = true)).isEqualTo(-40)
        assertThat(shownTemperature(celsius = 0.0, fahrenheit = true)).isEqualTo(32)
    }

    @Test fun onlyFahrenheitIsNotCelsius() {
        assertThat(isFahrenheit("fahrenhe")).isTrue()
        assertThat(isFahrenheit("celsius")).isFalse()
        // Kelvin can be chosen in Android's regional preferences; nobody reads the weather in it.
        assertThat(isFahrenheit("kelvin")).isFalse()
        assertThat(isFahrenheit("")).isFalse()
    }

    @Test fun theWidgetShowsFahrenheitWhereTheRegionUsesIt() {
        fahrenheit = true
        show(height = 280)

        // 12.4 degrees Celsius.
        compose.onNodeWithText("54°", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("12°", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theWidgetShowsCelsiusElsewhere() {
        fahrenheit = false
        show(height = 280)

        compose.onNodeWithText("12°", substring = true, useUnmergedTree = true).assertExists()
    }

    /** A clear night: the emoji is a moon, and a drawn icon is still named for the sky it shows. */
    @Test fun aClearNightShowsAMoon() {
        weather = CurrentWeather(temperatureC = 2.0, weatherCode = 0, city = "Oulu", fetchedAt = 0L, isDay = false)
        style = SmartspaceStyle(weatherIcon = LauncherSettings.WEATHER_ICON_EMOJI)
        show(height = 280)

        compose.onNodeWithText("🌙", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("☀️", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    /** Told nothing, the widget asks the phone: an American phone shows Fahrenheit, a Finnish one Celsius. */
    @Config(qualifiers = "en-rUS")
    @Test fun anAmericanPhoneShowsFahrenheit() {
        fahrenheit = null
        show(height = 280)

        compose.onNodeWithText("54°", substring = true, useUnmergedTree = true).assertExists()
    }

    @Config(qualifiers = "fi-rFI")
    @Test fun aFinnishPhoneShowsCelsius() {
        fahrenheit = null
        show(height = 280)

        compose.onNodeWithText("12°", substring = true, useUnmergedTree = true).assertExists()
    }

    /** The third size (the owner's word, 2026-11-19): larger than large, for the emoji and the drawn icons alike. */
    @Test fun theExtraLargeIconIsLargerThanTheLargeOne() {
        for (icon in LauncherSettings.WEATHER_ICON_STYLES) {
            style = SmartspaceStyle(weatherIcon = icon, weatherIconSize = LauncherSettings.WEATHER_SIZE_LARGE)
            if (icon == LauncherSettings.WEATHER_ICON_STYLES.first()) show(height = 320) else compose.waitForIdle()
            val large = glyphs().single().size.height

            style = SmartspaceStyle(weatherIcon = icon, weatherIconSize = LauncherSettings.WEATHER_SIZE_XLARGE)
            compose.waitForIdle()

            assertThat(glyphs().single().size.height).isGreaterThan(large)
        }
    }

    @Test fun theIconsSidesAreInTheOrderOfTheirNames() {
        assertThat(weatherIconFactor(LauncherSettings.WEATHER_SIZE_STANDARD)).isEqualTo(1.4f)
        assertThat(weatherIconFactor(LauncherSettings.WEATHER_SIZE_LARGE)).isEqualTo(2f)
        assertThat(weatherIconFactor(LauncherSettings.WEATHER_SIZE_XLARGE)).isEqualTo(2.8f)
        // Anything else is the standard one.
        assertThat(weatherIconFactor("gigantic")).isEqualTo(1.4f)
    }

    @Test fun automaticWidgetInkLeavesTheClockOnItsShippedVeilUnchanged() {
        var automatic by mutableStateOf(false)
        val state = SmartspaceState(hasCalendarPermission = true, nextEvent = null, hasLocationPermission = true,
            showWeather = true, weather = weather, weatherApp = weatherApp)
        compose.setContent {
            val bright = remember { HomeBackdrop(wallpaper = -1) }
            CompositionLocalProvider(LocalAutomaticWidgetWallpaper provides WallpaperLook(true, -1).takeIf { automatic }, LocalHomeBackdrop provides bright) {
                Box(Modifier.size(360.dp, 120.dp).background(Color.Gray)) {
                    SmartspaceContent(state, onRefresh = {}, onOpenWeatherApp = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }
        val before = brightestClockPixel()
        assertThat(before).isGreaterThan(0.85f)
        automatic = true
        compose.waitForIdle()
        assertThat(brightestClockPixel()).isWithin(0.01f).of(before)
    }

}
