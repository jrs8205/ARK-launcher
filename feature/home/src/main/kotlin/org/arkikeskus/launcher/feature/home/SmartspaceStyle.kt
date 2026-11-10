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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import org.arkikeskus.launcher.model.contentIsDarkOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
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
)

/** The widget's look as the settings have it. */
fun LauncherSettings.smartspaceStyle(): SmartspaceStyle = SmartspaceStyle(
    backgroundColor = smartspaceBackgroundColor,
    backgroundOpacity = smartspaceBackgroundOpacity,
    textScale = smartspaceTextScale,
    weatherIcon = weatherIconStyle,
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

/** The wallpaper's main colour, for choosing readable text on see-through cards over it. */
val LocalWallpaperColor = staticCompositionLocalOf { WALLPAPER_COLOR_UNKNOWN }

/**
 * The system wallpaper's primary colour as the platform reports it (no permission needed), kept up to
 * date when the wallpaper changes; [WALLPAPER_COLOR_UNKNOWN] until it is read or when there is none.
 */
@Composable
internal fun rememberWallpaperColor(): Int {
    val context = LocalContext.current
    var color by remember { mutableIntStateOf(WALLPAPER_COLOR_UNKNOWN) }
    DisposableEffect(context) {
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) color = colors?.primaryColor?.toArgb() ?: WALLPAPER_COLOR_UNKNOWN
        }
        runCatching { manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { manager.removeOnColorsChangedListener(listener) } }
    }
    LaunchedEffect(context) {
        // The first read may extract the colours from the image: off the main thread.
        val read = withContext(Dispatchers.IO) {
            runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() }.getOrNull()
        }
        if (read != null) color = read
    }
    return color
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

/**
 * The weather icon beside the temperature for every style but the standard emoji-in-the-text:
 * the emoji larger, or an icon drawn in colour, still or moving. Sized from the line's [fontSize].
 */
@Composable
internal fun WeatherGlyph(code: Int, iconStyle: String, fontSize: TextUnit, modifier: Modifier = Modifier) {
    if (iconStyle == LauncherSettings.WEATHER_ICON_LARGE) {
        Text(WeatherCodes.emoji(code), fontSize = fontSize * 1.7f, maxLines = 1, modifier = modifier.testTag(WEATHER_GLYPH_TAG))
        return
    }
    val size = with(LocalDensity.current) { (fontSize * 2f).toDp() }
    val phase = rememberWeatherPhase(animated = iconStyle == LauncherSettings.WEATHER_ICON_ANIMATED)
    val kind = WeatherCodes.kind(code)
    // The drawing says nothing to a screen reader by itself: it is named as the emoji was.
    val name = stringResource(weatherKindName(kind))
    Canvas(modifier.size(size).testTag(WEATHER_GLYPH_TAG).semantics { contentDescription = name }) {
        drawWeather(kind, phase.floatValue)
    }
}

private val SunCore = Color(0xFFFFC107)
private val SunRay = Color(0xFFFFB300)
private val CloudLight = Color(0xFFF5F7FA)
private val CloudShade = Color(0xFFB0BEC5)
private val CloudDark = Color(0xFF78909C)
private val RainDrop = Color(0xFF4FC3F7)
private val SnowFlake = Color(0xFFFFFFFF)
private val Bolt = Color(0xFFFFD54F)
private val FogLine = Color(0xFFCFD8DC)

private fun wave(t: Float, periodSeconds: Float): Float = sin(2f * PI.toFloat() * t / periodSeconds)
private fun cycle(t: Float, periodSeconds: Float, offset: Float = 0f): Float = (t / periodSeconds + offset).mod(1f)

/** One weather icon in the square of the canvas, [t] seconds into its animation (0 = the still pose). */
private fun DrawScope.drawWeather(kind: WeatherKind, t: Float) {
    val s = size.minDimension
    when (kind) {
        WeatherKind.CLEAR -> sun(Offset(s * 0.5f, s * 0.5f), s * 0.22f, t)
        WeatherKind.PARTLY_CLOUDY -> {
            sun(Offset(s * 0.36f, s * 0.36f), s * 0.16f, t)
            cloud(Offset(s * 0.56f + wave(t, 4f) * s * 0.03f, s * 0.62f), s * 0.62f, CloudLight)
        }
        WeatherKind.CLOUDY -> {
            cloud(Offset(s * 0.40f - wave(t, 4f) * s * 0.03f, s * 0.42f), s * 0.50f, CloudShade)
            cloud(Offset(s * 0.54f + wave(t, 4f) * s * 0.03f, s * 0.60f), s * 0.66f, CloudLight)
        }
        WeatherKind.FOG -> {
            cloud(Offset(s * 0.5f, s * 0.36f), s * 0.60f, CloudLight)
            for (i in 0..2) {
                val shift = wave(t + i * 0.7f, 4f) * s * 0.05f - wave(i * 0.7f, 4f) * s * 0.05f
                val y = s * (0.66f + i * 0.12f)
                drawLine(FogLine, Offset(s * 0.2f + shift, y), Offset(s * 0.8f + shift, y), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
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
            cloud(Offset(s * 0.5f, s * 0.38f), s * 0.68f, CloudShade)
        }
        WeatherKind.SNOW -> {
            for (i in 0..2) {
                val p = cycle(t, 2f, i / 3f)
                val x = s * (0.32f + i * 0.18f) + wave(t + i, 2f) * s * 0.02f - wave(i.toFloat(), 2f) * s * 0.02f
                drawCircle(SnowFlake.copy(alpha = 1f - p * 0.5f), s * 0.045f, Offset(x, s * (0.62f + p * 0.32f)))
            }
            cloud(Offset(s * 0.5f, s * 0.38f), s * 0.68f, CloudLight)
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
            drawPath(bolt, Bolt.copy(alpha = if (flash) 1f else 0.6f))
            cloud(Offset(s * 0.5f, s * 0.36f), s * 0.70f, CloudDark)
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
    drawCircle(SunCore, radius, center)
}

/** A cloud [width] wide around [center]: a rounded base under two bumps. */
private fun DrawScope.cloud(center: Offset, width: Float, color: Color) {
    val base = width * 0.32f
    drawRoundRect(color, Offset(center.x - width / 2f, center.y), Size(width, base), CornerRadius(base / 2f))
    drawCircle(color, width * 0.20f, Offset(center.x - width * 0.18f, center.y + base * 0.15f))
    drawCircle(color, width * 0.27f, Offset(center.x + width * 0.10f, center.y - width * 0.04f))
}

/** The glass panel's tints, white over the wallpaper: brighter at the [top] than the [bottom], and a
 *  lighter [edge] line, all following the strength setting. */
internal data class GlassAlphas(val top: Float, val bottom: Float, val edge: Float)

internal fun glassPanelAlphas(opacity: Float): GlassAlphas {
    val o = opacity.coerceIn(0f, 1f)
    return GlassAlphas(top = (o * 1.35f).coerceAtMost(1f), bottom = o * 0.7f, edge = (o * 2.2f + 0.12f).coerceAtMost(0.9f))
}

/**
 * A frosted glass panel behind the home grid, between the wallpaper and the icons (the owner's 1.0.5
 * idea): one rounded sheet with a soft sheen and a light edge. It tints; the frosting itself is the
 * system's wallpaper blur, which a window can only ask for as a whole.
 */
internal fun Modifier.homeGlassPanel(opacity: Float): Modifier = drawBehind {
    val alphas = glassPanelAlphas(opacity)
    val inset = 8.dp.toPx()
    val topLeft = Offset(inset, 0f)
    val panel = Size(size.width - 2 * inset, size.height)
    val corner = CornerRadius(28.dp.toPx())
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = alphas.top), Color.White.copy(alpha = alphas.bottom))),
        topLeft, panel, corner,
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = alphas.edge), Color.White.copy(alpha = alphas.edge * 0.25f))),
        topLeft, panel, corner, style = Stroke(width = 1.dp.toPx()),
    )
}
