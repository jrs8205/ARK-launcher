package org.arkikeskus.launcher.feature.home

import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.arkikeskus.launcher.data.smartspace.WeatherCodes
import org.arkikeskus.launcher.data.smartspace.WeatherKind
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.compositeOver
import org.arkikeskus.launcher.model.contentIsDarkOn
import org.arkikeskus.launcher.model.glassTint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The clock/weather widget's own look (Settings > Clock and weather widget): its card's colour
 * ([backgroundColor], ARGB; null = the dark veil, or the theme's surface with the tonal setting) at
 * [backgroundOpacity], the multiplier on its text sizes, and how the weather icon is shown (one of
 * [LauncherSettings.WEATHER_ICON_STYLES]).
 */
data class SmartspaceStyle(
    val backgroundColor: Int? = null,
    val backgroundOpacity: Float = LauncherSettings.DEFAULT_SMARTSPACE_BACKGROUND,
    val textScale: Float = 1f,
    val weatherIcon: String = LauncherSettings.WEATHER_ICON_EMOJI,
    /** The large icon: a choice of its own, for every style. */
    val weatherIconLarge: Boolean = false,
)

/** The widget's look as the settings have it. */
fun LauncherSettings.smartspaceStyle(): SmartspaceStyle = SmartspaceStyle(
    backgroundColor = smartspaceBackgroundColor,
    backgroundOpacity = smartspaceBackgroundOpacity,
    textScale = smartspaceTextScale,
    weatherIcon = weatherIconStyle,
    weatherIconLarge = weatherIconLarge,
)

val LocalSmartspaceStyle = staticCompositionLocalOf { SmartspaceStyle() }

internal const val WEATHER_GLYPH_TAG = "weatherGlyph"
internal const val SMARTSPACE_CLOCK_TAG = "smartspaceClock"

/**
 * The card's colour: the user's own at the opacity; without one the [default] veil at the opacity,
 * except the theme's surface ([tonal]), which stays as it is.
 */
internal fun smartspaceSurface(style: SmartspaceStyle, default: Color, tonal: Boolean): Color = when {
    style.backgroundColor != null -> Color(style.backgroundColor).copy(alpha = style.backgroundOpacity)
    tonal -> default
    else -> default.copy(alpha = style.backgroundOpacity)
}

/** The text colour: the [default] without a colour of the user's; on one, black or white by its contrast
 *  with what the card looks like over the [wallpaper]'s colour (a faint card goes by the wallpaper). */
internal fun smartspaceInk(style: SmartspaceStyle, default: Color, wallpaper: Int): Color {
    val argb = style.backgroundColor ?: return default
    return if (contentIsDarkOn(argb, style.backgroundOpacity, wallpaper)) Color.Black else Color.White
}

/** The wallpaper's main colour (ARGB) while it is not known, or has none to tell: a middle grey. */
internal const val WALLPAPER_COLOR_UNKNOWN = 0xFF808080.toInt()

/**
 * Where the glass panel is in the window (px from the window's top), as its own modifier last laid it
 * out. Snapshot state: a card that reads it re-picks its colours when the panel moves or resizes.
 */
@Stable
class GlassPanelBounds {
    var top by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)

    /** How far down the panel the point [y] px down the window is: 0 = its top, 1 = its bottom, in steps of
     *  a twentieth (a card that moves a little keeps its colours); the middle while the panel is not laid out. */
    fun fractionOf(y: Float): Float {
        if (height <= 0f) return 0.5f
        return (((y - top) / height).coerceIn(0f, 1f) * 20f).roundToInt() / 20f
    }
}

/**
 * What is behind the home screen's cards, for choosing readable text on see-through ones: the
 * [wallpaper]'s colour and, when the glass panel is on ([glassOpacity], null without it), the panel's
 * white tint over it. The tint fades from the panel's top to its bottom, so the colour depends on how
 * far down the [panel] a card sits.
 */
@Stable
data class HomeBackdrop(
    val wallpaper: Int = WALLPAPER_COLOR_UNKNOWN,
    val glassOpacity: Float? = null,
    val panel: GlassPanelBounds = GlassPanelBounds(),
) {
    /** The colour behind a card whose centre is [centerY] px down the window (null = not placed yet: the
     *  panel's middle). */
    fun colorBehind(centerY: Float?): Int =
        homeBackdropColor(wallpaper, glassOpacity, if (centerY == null || glassOpacity == null) 0.5f else panel.fractionOf(centerY))
}

val LocalHomeBackdrop = staticCompositionLocalOf { HomeBackdrop() }

/**
 * The wallpaper's colour as it becomes known: from the first read (slow, off the main thread) and from
 * the system's announcements of a change. An announcement is always the newer word, so a first read
 * that finishes after one is dropped.
 */
internal class WallpaperColorState {
    var color by mutableIntStateOf(WALLPAPER_COLOR_UNKNOWN)
        private set
    private var announced = false

    /** The system says the wallpaper's colours changed to [argb] (null = it has none to tell). */
    fun onAnnounced(argb: Int?) {
        announced = true
        color = argb ?: WALLPAPER_COLOR_UNKNOWN
    }

    /** The first read came back with [argb] (null = nothing read). */
    fun onFirstRead(argb: Int?) {
        if (!announced && argb != null) color = argb
    }
}

/**
 * The system wallpaper's primary colour as the platform reports it (no permission needed), kept up to
 * date when the wallpaper changes; [WALLPAPER_COLOR_UNKNOWN] until it is read or when there is none.
 */
@Composable
internal fun rememberWallpaperColor(): Int {
    val context = LocalContext.current
    val state = remember { WallpaperColorState() }
    DisposableEffect(context) {
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) state.onAnnounced(colors?.primaryColor?.toArgb())
        }
        runCatching { manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { manager.removeOnColorsChangedListener(listener) } }
    }
    LaunchedEffect(context) {
        // The first read may extract the colours from the image: off the main thread.
        val read = withContext(Dispatchers.IO) {
            runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() }.getOrNull()
        }
        state.onFirstRead(read)
    }
    return state.color
}

/**
 * What is behind a card on the home screen (ARGB): the [wallpaper]'s colour, under the glass panel's white
 * tint when the layer is on ([glassOpacity], null without it). The tint is the layer's own gradient
 * ([glassTint]) at [fraction] of its height (0 = top, 1 = bottom): it is brighter at the top, so the
 * same card can need dark text high on it and light text low on it.
 */
internal fun homeBackdropColor(wallpaper: Int, glassOpacity: Float?, fraction: Float = 0.5f): Int {
    if (glassOpacity == null) return wallpaper
    return compositeOver(0xFFFFFFFF.toInt(), glassTint(glassOpacity).at(fraction), wallpaper)
}

/** The name of a kind of weather, for a screen reader: a drawn icon has no text of its own. */
@StringRes
internal fun weatherKindName(kind: WeatherKind): Int = when (kind) {
    WeatherKind.CLEAR -> R.string.weather_clear
    WeatherKind.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
    WeatherKind.CLOUDY -> R.string.weather_cloudy
    WeatherKind.FOG -> R.string.weather_fog
    WeatherKind.RAIN -> R.string.weather_rain
    WeatherKind.SNOW -> R.string.weather_snow
    WeatherKind.THUNDER -> R.string.weather_thunder
}

/** How long the moving weather icon moves after the home screen comes back, then it rests (battery).
 *  Every motion's period divides it, so the icon comes to rest exactly in its still pose. */
internal const val WEATHER_ANIMATION_MS = 8_000L

/** Whether the icon is still moving [elapsedMs] after the home screen came back. The system's
 *  "Remove animations" ([systemAnimations] false) stills it altogether. */
internal fun weatherAnimationRunning(elapsedMs: Long, animated: Boolean, systemAnimations: Boolean): Boolean =
    animated && systemAnimations && elapsedMs < WEATHER_ANIMATION_MS

/** Seconds into the current run of the animation; 0 at rest. Read in the draw phase only. */
@Composable
private fun rememberWeatherPhase(animated: Boolean): FloatState {
    val phase = remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    var run by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(animated) {
        run++
        onPauseOrDispose { }
    }
    LaunchedEffect(animated, run) {
        try {
            if (!animated) return@LaunchedEffect
            val systemAnimations =
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            val start = withFrameMillis { it }
            while (true) {
                val elapsed = withFrameMillis { it } - start
                if (!weatherAnimationRunning(elapsed, animated, systemAnimations)) break
                phase.floatValue = elapsed / 1000f
            }
        } finally {
            phase.floatValue = 0f
        }
    }
    return phase
}

/** The drawn icon's side as a multiple of the line's font size: about an emoji's, and the large one. */
internal const val WEATHER_ICON_STANDARD = 1.4f
internal const val WEATHER_ICON_LARGE = 2f

/**
 * The weather icon beside the temperature whenever it is not the standard emoji in the text: the emoji
 * [large], or an icon drawn in colour, still or moving, at the standard size or [large]. Sized from the
 * line's [fontSize].
 */
@Composable
internal fun WeatherGlyph(code: Int, iconStyle: String, large: Boolean, fontSize: TextUnit, modifier: Modifier = Modifier) {
    if (iconStyle == LauncherSettings.WEATHER_ICON_EMOJI) {
        Text(WeatherCodes.emoji(code), fontSize = fontSize * 1.7f, maxLines = 1, modifier = modifier.testTag(WEATHER_GLYPH_TAG))
        return
    }
    val size = with(LocalDensity.current) { (fontSize * (if (large) WEATHER_ICON_LARGE else WEATHER_ICON_STANDARD)).toDp() }
    val phase = rememberWeatherPhase(animated = iconStyle == LauncherSettings.WEATHER_ICON_ANIMATED)
    val kind = WeatherCodes.kind(code)
    // The drawing says nothing to a screen reader by itself: it is named as the emoji was.
    val name = stringResource(weatherKindName(kind))
    Canvas(modifier.size(size).testTag(WEATHER_GLYPH_TAG).semantics { contentDescription = name }) {
        drawWeather(kind, phase.floatValue)
    }
}

// Vivid on purpose: this is the style chosen for colour (the first set, mostly white and grey clouds, read as pale).
private val SunCentre = Color(0xFFFFEA00)
private val SunEdge = Color(0xFFFF9100)
private val SunRay = Color(0xFFFF6D00)
private val FairCloud = listOf(Color(0xFFE3F2FD), Color(0xFF42A5F5))
private val BackCloud = listOf(Color(0xFF90CAF9), Color(0xFF1565C0))
private val FogCloud = listOf(Color(0xFFE0F7FA), Color(0xFF26C6DA))
private val FogLines = listOf(Color(0xFF80DEEA), Color(0xFF4DD0E1), Color(0xFF26C6DA))
private val RainCloud = listOf(Color(0xFF90CAF9), Color(0xFF3949AB))
private val RainDrop = Color(0xFF00B0FF)
private val SnowCloud = listOf(Color(0xFFE1F5FE), Color(0xFF29B6F6))
private val SnowFlake = Color(0xFFFFFFFF)
private val SnowRim = Color(0xFF29B6F6)
private val StormCloud = listOf(Color(0xFFB39DDB), Color(0xFF4527A0))
private val BoltTop = Color(0xFFFFEA00)
private val BoltBottom = Color(0xFFFF9100)

private fun wave(t: Float, periodSeconds: Float): Float = sin(2f * PI.toFloat() * t / periodSeconds)
private fun cycle(t: Float, periodSeconds: Float, offset: Float = 0f): Float = (t / periodSeconds + offset).mod(1f)

/** One weather icon in the square of the canvas, [t] seconds into its animation (0 = the still pose). */
private fun DrawScope.drawWeather(kind: WeatherKind, t: Float) {
    val s = size.minDimension
    when (kind) {
        WeatherKind.CLEAR -> sun(Offset(s * 0.5f, s * 0.5f), s * 0.22f, t)
        WeatherKind.PARTLY_CLOUDY -> {
            sun(Offset(s * 0.36f, s * 0.36f), s * 0.16f, t)
            cloud(Offset(s * 0.56f + wave(t, 4f) * s * 0.03f, s * 0.62f), s * 0.62f, FairCloud)
        }
        WeatherKind.CLOUDY -> {
            cloud(Offset(s * 0.40f - wave(t, 4f) * s * 0.03f, s * 0.42f), s * 0.50f, BackCloud)
            cloud(Offset(s * 0.54f + wave(t, 4f) * s * 0.03f, s * 0.60f), s * 0.66f, FairCloud)
        }
        WeatherKind.FOG -> {
            cloud(Offset(s * 0.5f, s * 0.36f), s * 0.60f, FogCloud)
            for (i in 0..2) {
                val shift = wave(t + i * 0.7f, 4f) * s * 0.05f - wave(i * 0.7f, 4f) * s * 0.05f
                val y = s * (0.66f + i * 0.12f)
                drawLine(FogLines[i], Offset(s * 0.2f + shift, y), Offset(s * 0.8f + shift, y), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
            }
        }
        WeatherKind.RAIN -> {
            for (i in 0..2) {
                val p = cycle(t, 1f, i / 3f)
                val x = s * (0.32f + i * 0.18f)
                val y = s * (0.60f + p * 0.30f)
                drawLine(
                    RainDrop.copy(alpha = 1f - p * 0.6f), Offset(x, y), Offset(x - s * 0.04f, y + s * 0.10f),
                    strokeWidth = s * 0.06f, cap = StrokeCap.Round,
                )
            }
            cloud(Offset(s * 0.5f, s * 0.38f), s * 0.68f, RainCloud)
        }
        WeatherKind.SNOW -> {
            for (i in 0..2) {
                val p = cycle(t, 2f, i / 3f)
                val x = s * (0.32f + i * 0.18f) + wave(t + i, 2f) * s * 0.02f - wave(i.toFloat(), 2f) * s * 0.02f
                // A white flake in a blue rim, so it shows on a light wallpaper too.
                val flake = Offset(x, s * (0.62f + p * 0.32f))
                drawCircle(SnowRim.copy(alpha = 1f - p * 0.5f), s * 0.055f, flake)
                drawCircle(SnowFlake.copy(alpha = 1f - p * 0.5f), s * 0.032f, flake)
            }
            cloud(Offset(s * 0.5f, s * 0.38f), s * 0.68f, SnowCloud)
        }
        WeatherKind.THUNDER -> {
            val c = cycle(t, 2f)
            val flash = c < 0.12f || (c > 0.20f && c < 0.27f)
            val bolt = Path().apply {
                moveTo(s * 0.54f, s * 0.52f)
                lineTo(s * 0.40f, s * 0.76f)
                lineTo(s * 0.50f, s * 0.76f)
                lineTo(s * 0.44f, s * 0.96f)
                lineTo(s * 0.64f, s * 0.68f)
                lineTo(s * 0.53f, s * 0.68f)
                close()
            }
            drawPath(
                bolt,
                Brush.verticalGradient(listOf(BoltTop, BoltBottom), startY = s * 0.52f, endY = s * 0.96f),
                alpha = if (flash) 1f else 0.7f,
            )
            cloud(Offset(s * 0.5f, s * 0.36f), s * 0.70f, StormCloud)
        }
    }
}

/** A sun: the disc and eight rays that turn once in eight seconds. */
private fun DrawScope.sun(center: Offset, radius: Float, t: Float) {
    rotate(degrees = t / 8f * 360f, pivot = center) {
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f
            val dir = Offset(cos(a), sin(a))
            drawLine(SunRay, center + dir * (radius * 1.4f), center + dir * (radius * 1.85f), strokeWidth = radius * 0.24f, cap = StrokeCap.Round)
        }
    }
    drawCircle(Brush.radialGradient(listOf(SunCentre, SunEdge), center = center, radius = radius), radius, center)
}

/** A cloud [width] wide around [center]: a rounded base under two bumps, shaded from [colors]' first at its
 *  top to their last at its bottom (one brush over the three shapes, so they read as one body). */
private fun DrawScope.cloud(center: Offset, width: Float, colors: List<Color>) {
    val base = width * 0.32f
    val brush = Brush.verticalGradient(colors, startY = center.y - width * 0.31f, endY = center.y + base)
    drawRoundRect(brush, Offset(center.x - width / 2f, center.y), Size(width, base), CornerRadius(base / 2f))
    drawCircle(brush, width * 0.20f, Offset(center.x - width * 0.18f, center.y + base * 0.15f))
    drawCircle(brush, width * 0.27f, Offset(center.x + width * 0.10f, center.y - width * 0.04f))
}

/**
 * A frosted glass layer between the wallpaper and the home screen (the owner's 1.0.5 idea): one sheet
 * over the whole screen, from its top edge to its bottom edge, under the status bar, the icons, the dock
 * and the navigation bar, with a soft sheen that fades downwards ([glassTint]). It tints; the frosting
 * itself is the system's wallpaper blur, which a window can only ask for as a whole. Where it is laid
 * out goes to [bounds], so the cards over it can tell which part of its fading tint is behind them.
 */
internal fun Modifier.homeGlassPanel(opacity: Float, bounds: GlassPanelBounds): Modifier = onGloballyPositioned {
    bounds.top = it.positionInRoot().y
    bounds.height = it.size.height.toFloat()
}.drawBehind {
    val tint = glassTint(opacity)
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = tint.top), Color.White.copy(alpha = tint.bottom))))
}
