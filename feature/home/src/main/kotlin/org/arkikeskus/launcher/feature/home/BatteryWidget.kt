package org.arkikeskus.launcher.feature.home

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.graphics.lerp
import org.arkikeskus.launcher.launcher.system.BatteryMonitor
import org.arkikeskus.launcher.model.BatteryStatus
import javax.inject.Inject

@HiltViewModel
class BatteryWidgetViewModel @Inject constructor(
    batteryMonitor: BatteryMonitor,
) : ViewModel() {
    /** The same ACTION_BATTERY_CHANGED stream the status bar renders — the system's own rhythm,
     *  updated on every percent/charging change, so this can never lag the official indicator. */
    val status: StateFlow<BatteryStatus?> = batteryMonitor.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

// The ring's own vivid ramp (user feedback: the status bar's shared battery palette read pale on
// the large ring) — neon-bright A400-style stops, deliberately NOT LauncherColors so the status
// bar keeps its calmer tones.
private val RingLow = Color(0xFFFF1744)
private val RingMedium = Color(0xFFFFEA00)
private val RingHigh = Color(0xFF00FF6A)

private fun ringColor(percent: Int): Color {
    val p = percent.coerceIn(0, 100)
    return if (p <= 50) lerp(RingLow, RingMedium, p / 50f) else lerp(RingMedium, RingHigh, (p - 50) / 50f)
}

/**
 * The built-in battery widget: an app-icon-sized ring gauge with the percent in the middle and a
 * bolt while charging, in a vivid red→yellow→green ramp. Scales with its footprint like the other
 * built-ins. Tap opens the system battery screen.
 */
@Composable
fun BatteryWidget(
    modifier: Modifier = Modifier,
    viewModel: BatteryWidgetViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val noIndication = remember { MutableInteractionSource() }
    val shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f)

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val s = status ?: return@BoxWithConstraints
        // The ring hugs the smaller cell axis, like an app icon does; resizing the footprint scales it.
        val diameter = minOf(maxWidth, maxHeight) * 0.84f
        val color = ringColor(s.percent)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(diameter)
                .clickable(interactionSource = noIndication, indication = null) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.10f
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                // Translucent disc so the ring + number read on any wallpaper (the card idiom).
                drawCircle(color = Color.Black.copy(alpha = 0.30f))
                drawArc(
                    color = Color.White.copy(alpha = 0.25f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = -90f, sweepAngle = 360f * s.percent.coerceIn(0, 100) / 100f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.charging) {
                    Icon(
                        painter = painterResource(R.drawable.ic_status_charging),
                        contentDescription = stringResource(R.string.status_desc_charging),
                        tint = color,
                        modifier = Modifier.size(diameter * 0.17f),
                    )
                    Spacer(Modifier.width(diameter * 0.02f))
                }
                Text(
                    text = "${s.percent.coerceIn(0, 100)}%",
                    color = Color.White,
                    style = TextStyle(
                        fontSize = with(density) { (diameter * 0.23f).toSp() },
                        fontWeight = FontWeight.Medium,
                        shadow = shadow,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
