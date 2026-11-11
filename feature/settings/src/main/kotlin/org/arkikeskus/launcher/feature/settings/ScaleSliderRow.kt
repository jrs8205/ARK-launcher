package org.arkikeskus.launcher.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import kotlin.math.roundToInt

/** Slider tick count that lands the thumb on multiples of [step] across [range] (Material counts
 *  only the ticks strictly between the two ends). */
internal fun scaleSliderSteps(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)

/**
 * The system's own long-press timeout (Android's accessibility "Touch & hold delay"), re-read every
 * time the screen resumes: it is not Compose state, and the user can change it in the system
 * settings while this screen waits in the background.
 */
@Composable
internal fun rememberSystemLongPressTimeout(): Long {
    val configuration = LocalViewConfiguration.current
    var timeout by remember(configuration) { mutableLongStateOf(configuration.longPressTimeoutMillis) }
    LifecycleResumeEffect(configuration) {
        timeout = configuration.longPressTimeoutMillis
        onPauseOrDispose { }
    }
    return timeout
}

/** A size multiplier as a whole percent (1.149 → 115). */
internal fun scalePercent(value: Float): Int = (value * 100f).roundToInt()

/**
 * A settings slider for a multiplier setting (icon size, label size, long-press delay): the current
 * value is shown beside the label in the words of [valueText], the thumb snaps to [step]
 * increments with a tick of haptic feedback on every change so a move is felt, and a "Default …"
 * chip under the slider appears whenever the value is off [default] to bring it straight back.
 */
@Composable
internal fun ScaleSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    description: String? = null,
    step: Float = 0.05f,
    default: Float = 1f,
) {
    val p = LocalExpressivePalette.current
    val haptics = LocalHapticFeedback.current
    val shown = valueText(value)
    // The readout last reported within the gesture in progress, null between gestures. A tick is felt
    // when the new readout differs from it — or, at the start of a gesture, from what is shown — so a
    // slider that repeats the same snapped value while the stored setting catches up ticks once, and a
    // value that arrived from outside (the stored setting loading, a restore) is judged as shown.
    var feltBase by remember { mutableStateOf<String?>(null) }
    val change: (Float) -> Unit = { next ->
        val text = valueText(next)
        if (text != (feltBase ?: shown)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        feltBase = text
        onValueChange(next)
    }
    Surface(
        color = p.surfaceHi,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = p.shadow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = p.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(
                    shown,
                    color = p.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (description != null) {
                Text(description, color = p.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Slider(
                value = value,
                onValueChange = change,
                onValueChangeFinished = { feltBase = null },
                valueRange = valueRange,
                steps = scaleSliderSteps(valueRange, step),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = p.trackOff,
                    activeTickColor = Accent,
                    inactiveTickColor = p.trackOff,
                ),
            )
            // Below the slider, so its appearance never moves the thumb away from the finger.
            if (shown != valueText(default)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            change(default)
                            feltBase = null
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.settings_reset_default, valueText(default)), color = p.accent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
