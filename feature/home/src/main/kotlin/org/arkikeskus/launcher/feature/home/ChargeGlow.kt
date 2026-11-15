package org.arkikeskus.launcher.feature.home

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.launcher.system.BatteryMonitor
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import javax.inject.Inject

/*
 * The charging animation (a Telegram wish; the owner's choice of two parts): a glow that rises from the
 * bottom of the home screen when the charger is connected, and the battery glyph of the launcher's own
 * status bar filling while it charges. One switch (Settings, Appearance) turns both off, and neither moves
 * when the system's animations are off.
 */

/** The glow's rise and its fade, in milliseconds: a little over a second in all. */
private const val CHARGE_GLOW_RISE_MS = 320
private const val CHARGE_GLOW_FADE_MS = 780

/** How far up the screen the glow reaches at its strongest, and how opaque it is at the bottom edge then. */
private const val CHARGE_GLOW_HEIGHT = 0.42f
private const val CHARGE_GLOW_ALPHA = 0.6f

/** One sweep of the battery glyph: from the level to full, a rest at full, and again. */
private const val CHARGE_SWEEP_MS = 1800
private const val CHARGE_SWEEP_REST_MS = 800

/**
 * Whether the glow starts: the battery is charging [now] and was not [before]. The first word on the
 * battery (no [before]) says nothing about a change: a home screen that comes up on a phone that is
 * already charging does not glow.
 */
internal fun chargeGlowStarts(before: Boolean?, now: Boolean): Boolean = before == false && now

/** False when the user has switched the system's animations off (Developer options, or "Remove animations"). */
internal fun systemAnimationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** The glow's state. [strength] is read where the glow is drawn, so a frame of it is a redraw and no recomposition. */
@Stable
internal class ChargeGlowState {
    private val animation = Animatable(0f)

    /** 0 = nothing is drawn, 1 = the glow at its strongest. */
    val strength: Float get() = animation.value

    /** Plays the glow once from the start: up, then away. */
    suspend fun play() {
        animation.snapTo(0f)
        animation.animateTo(1f, tween(CHARGE_GLOW_RISE_MS, easing = LinearOutSlowInEasing))
        animation.animateTo(0f, tween(CHARGE_GLOW_FADE_MS, easing = FastOutLinearInEasing))
    }

    suspend fun clear() = animation.snapTo(0f)
}

/**
 * The glow for a screen: it plays when [charging] turns true while the screen is in front (resumed).
 * [charging] has to be a flow that begins every collection with the battery's state as it is then, as the
 * battery monitor's does: the change is looked for only among the values heard while in front, so a charger
 * connected while another app was open never glows on the way back.
 */
@Composable
internal fun rememberChargeGlow(charging: Flow<Boolean>, enabled: Boolean): ChargeGlowState {
    val state = remember { ChargeGlowState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(charging, enabled, lifecycle) {
        if (!enabled) {
            state.clear()
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                var before: Boolean? = null
                charging.collect { now ->
                    val starts = chargeGlowStarts(before, now)
                    before = now
                    // Launched, not awaited: the battery's next word is heard while the glow plays.
                    if (starts && systemAnimationsOn(context)) launch { state.play() }
                }
            } finally {
                // Leaving the screen stops the glow halfway; it must not be standing there on the way back.
                withContext(NonCancellable) { state.clear() }
            }
        }
    }
    return state
}

/** The glow itself: the [color] rising from the bottom edge. Draws nothing while idle, and takes no touches. */
@Composable
internal fun ChargeGlow(state: ChargeGlowState, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val strength = state.strength
        if (strength <= 0f) return@Canvas
        val height = size.height * CHARGE_GLOW_HEIGHT * (0.55f + 0.45f * strength)
        val top = size.height - height
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to color.copy(alpha = CHARGE_GLOW_ALPHA * strength),
                startY = top,
                endY = size.height,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, height),
        )
    }
}

/** What the home screen's glow listens to. */
@HiltViewModel
class ChargeGlowViewModel @Inject constructor(battery: BatteryMonitor) : ViewModel() {
    /** Cold: every collection begins with the battery's state as it is then (see [rememberChargeGlow]). */
    val charging: Flow<Boolean> = battery.status.map { it.charging }.distinctUntilChanged()
}

/** The home screen's layer for the glow, in the theme's colour; [enabled] is the charging animation's switch. */
@Composable
internal fun ChargeGlowLayer(enabled: Boolean, modifier: Modifier = Modifier, viewModel: ChargeGlowViewModel = hiltViewModel()) {
    ChargeGlow(rememberChargeGlow(viewModel.charging, enabled), LocalExpressivePalette.current.accent, modifier)
}

/**
 * The battery glyph's sweep while charging, to hand to the glyph: from 0 to 1 in a little under two seconds,
 * a rest, and again. Null when nothing is to move: not [active] (not charging, or the switch is off), or the
 * system's animations are off. With them off an endless animation would jump to its end, a glyph drawn full.
 */
@Composable
internal fun rememberChargeSweep(active: Boolean): (() -> Float)? {
    val context = LocalContext.current
    if (!active || !systemAnimationsOn(context)) return null
    val sweep = rememberInfiniteTransition(label = "charging").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = CHARGE_SWEEP_MS + CHARGE_SWEEP_REST_MS
                0f at 0 using LinearEasing
                1f at CHARGE_SWEEP_MS
                1f at CHARGE_SWEEP_MS + CHARGE_SWEEP_REST_MS
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    return remember(sweep) { { sweep.value } }
}
