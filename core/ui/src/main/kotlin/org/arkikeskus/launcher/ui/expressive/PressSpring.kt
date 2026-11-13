package org.arkikeskus.launcher.ui.expressive

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/*
 * The small motion of the new look: what is pressed shrinks a little and springs back. Only on buttons, category
 * rows and swatches: never on anything that is dragged or swiped (home and drawer icons, the folder card), whose
 * gestures have rules of their own. With the phone's animations switched off nothing moves.
 */

/** How small a pressed thing gets. */
const val PRESSED_SCALE = 0.96f

private val PressSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 800f)

/** Whether the phone's animations are on: the system's animator duration scale is not 0. Read once per screen. */
@Composable
fun systemAnimationsOn(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
    }
}

/**
 * 1 at rest and [PRESSED_SCALE] while [interactionSource] is pressed, moving between the two on a spring; always
 * 1 when [animate] is false (the phone's animations are off). Read the value where it is drawn, not in composition.
 */
@Composable
fun rememberPressScale(interactionSource: InteractionSource, animate: Boolean = systemAnimationsOn()): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed && animate) PRESSED_SCALE else 1f,
        animationSpec = PressSpec,
        label = "press",
    )
}

/** The springy press of what [interactionSource] belongs to: place it before the clip and the background. */
@Composable
fun Modifier.pressSpring(interactionSource: InteractionSource): Modifier {
    val scale = rememberPressScale(interactionSource)
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
