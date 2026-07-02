package org.arkikeskus.launcher.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.StatusNotification
import org.arkikeskus.launcher.designsystem.component.BatteryBar
import org.arkikeskus.launcher.designsystem.component.SignalBars
import org.arkikeskus.launcher.designsystem.theme.LocalLauncherColors
import org.arkikeskus.launcher.launcher.system.BatteryMonitor
import org.arkikeskus.launcher.launcher.system.ConnectivityMonitor
import org.arkikeskus.launcher.launcher.system.SignalMonitor
import org.arkikeskus.launcher.launcher.system.SystemFlagsMonitor
import org.arkikeskus.launcher.model.BatteryStatus
import org.arkikeskus.launcher.model.MobileStatus
import org.arkikeskus.launcher.model.SystemFlags
import org.arkikeskus.launcher.model.WifiBand
import org.arkikeskus.launcher.model.WifiStatus
import java.util.Date
import javax.inject.Inject

/** Combined live system status driving the home status bar. */
data class StatusBarState(
    val battery: BatteryStatus = BatteryStatus(percent = 100, charging = false),
    val wifi: WifiStatus = WifiStatus(connected = false, level = 0, band = WifiBand.UNKNOWN),
    val mobile: MobileStatus = MobileStatus(active = false, level = 0, generation = null),
    val flags: SystemFlags = SystemFlags(),
    val notifications: List<StatusNotification> = emptyList(),
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    battery: BatteryMonitor,
    connectivity: ConnectivityMonitor,
    signal: SignalMonitor,
    flags: SystemFlagsMonitor,
    badges: NotificationBadgeRepository,
) : ViewModel() {
    val state: StateFlow<StatusBarState> =
        combine(battery.status, connectivity.wifi, signal.mobile, flags.flags, badges.icons) { b, w, m, f, n ->
            StatusBarState(b, w, m, f, n)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusBarState())
}

private val STATUS_EDGE_PAD = 28.dp // clock / battery inset from the screen edges
private val STATUS_GAP = 8.dp // space between indicators
private val STATUS_NOTIF_GAP = 5.dp // space between notification icons on the left
private val STATUS_MAIN_LINE = 18.dp // common centre line for every primary glyph (matches the stock bar)
private val STATUS_FADE = 8.dp // soft bottom edge below the glyphs where the scrim fades into the wallpaper

// Tight text: drop the default font padding so the glyphs centre on their true visual bounds, level
// with the geometrically-centred icons on the shared main line (otherwise the clock digits sit low).
private val StatusTextStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

/**
 * A slim home status bar. Clock on the left; on the right, every currently-active phone indicator —
 * status flags (alarm, DND, silent/vibrate, location, NFC, data-saver, Bluetooth, VPN, airplane),
 * the mobile signal (bars + 5G/4G/LTE), Wi-Fi and the battery (bar + percentage). Two-part readouts
 * (mobile, battery) stack vertically to save width; every primary glyph shares one centre line.
 *
 * A custom layout places the clock at the left edge and the indicators from the right edge inward,
 * flowing **around a top display cutout** (punch-hole) — when a glyph would land under the cutout it
 * jumps to the cutout's left side instead of being hidden behind it.
 *
 * Colours are dynamic: battery + signal/Wi-Fi use the vivid quality ramp from [LocalLauncherColors]
 * (≤20% red / ≤45% yellow / >45% green; signal 0–4 red→green); binary flags follow the Material You
 * accent. A dark top scrim ([scrimAlpha], user-configurable) keeps every glyph legible on bright
 * wallpapers.
 *
 * [topInset] reserves space at the top for a still-visible system status bar: the scrim is drawn on the
 * outer box so it fills that inset too and reaches the very top edge (no gap behind the system bar),
 * while the glyphs are pushed below it. When we own the whole top zone (system bar hidden) [topInset]
 * is 0 and [alignToCutout] instead drops the glyph line to the camera's vertical centre.
 */
@Composable
fun StatusBar(
    modifier: Modifier = Modifier,
    alignToCutout: Boolean = false,
    topInset: Dp = 0.dp,
    scrimAlpha: Float = 0.6f,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalLauncherColors.current
    val accent = MaterialTheme.colorScheme.primary
    val time = rememberClock()
    val view = LocalView.current
    val density = LocalDensity.current

    // The top punch-hole rect (px): left/right let the row flow around it; the vertical centre lets the
    // glyphs sit level with the camera. Null when there is no cutout.
    var cutout by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // Re-read when the cutout insets change: a foldable's fold/unfold swaps displays WITHOUT
    // recreating the activity (configChanges), so a once-per-view read went stale and the glyphs
    // could sit under the other display's camera. Reading the reactive Compose insets here makes
    // recomposition re-key the effect whenever the cutout actually moves.
    val cutoutInsets = WindowInsets.displayCutout
    val cutoutKey = with(density) {
        cutoutInsets.getTop(this) +
            cutoutInsets.getLeft(this, androidx.compose.ui.unit.LayoutDirection.Ltr) +
            cutoutInsets.getRight(this, androidx.compose.ui.unit.LayoutDirection.Ltr)
    }
    LaunchedEffect(view, cutoutKey) {
        val dc = view.rootWindowInsets?.displayCutout
        val top = dc?.boundingRectTop?.takeIf { it.width() > 0 }
        // boundingRectTop can be looser than the visible camera (its centre then sits off the hole), so
        // refine to the tight cutout PATH bounds when they fall inside the top band — that gives the real
        // punch-hole centre on any device. API 31+; falls back to the rect otherwise.
        val pathRect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            dc?.cutoutPath?.let { p ->
                val rf = android.graphics.RectF()
                p.computeBounds(rf, true)
                if (rf.width() > 0f && rf.height() > 0f) {
                    android.graphics.Rect(rf.left.toInt(), rf.top.toInt(), rf.right.toInt(), rf.bottom.toInt())
                } else {
                    null
                }
            }
        } else {
            null
        }
        cutout = when {
            pathRect != null && top != null && pathRect.centerY() in top.top..top.bottom -> pathRect
            else -> top
        }
    }
    // The bar's own top in window space, so cutout alignment is exact regardless of where the bar is
    // placed (the cutout rect is in window coordinates too).
    var barTopInWindow by remember { mutableStateOf(0) }

    val f = s.flags
    val batteryColor = colors.batteryColor(s.battery.percent)
    val shownNotifs = s.notifications.take(5)
    val notifCount = shownNotifs.size

    Box(
        modifier = modifier
            .fillMaxWidth()
            // No single colour contrasts with every wallpaper, so back the bar with a dark scrim.
            // It stays fully [scrimAlpha]-dark from the top edge (behind a still-visible system status
            // bar — no gap) down THROUGH the glyphs, then fades to transparent only in the bottom
            // [STATUS_FADE] strip so it blends into the wallpaper. The dark therefore sits under our bar's
            // clock/icons, not stranded up at the very top edge (the earlier bug).
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = scrimAlpha),
                    0.82f to Color.Black.copy(alpha = scrimAlpha),
                    1f to Color.Transparent,
                ),
            ),
    ) {
    // Pin the glyph text to a fixed size: the status bar must NOT grow with the system font-size
    // (accessibility) setting — a larger font would overflow the compact custom layout and break it.
    // fontScale = 1 makes every sp inside render at its dp-equivalent, like the stock status bar.
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topInset, bottom = STATUS_FADE)
            .onGloballyPositioned { barTopInWindow = it.positionInWindow().y.toInt() },
        content = {
            // [0] clock (left edge).
            MainLineBox {
                Text(time, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = StatusTextStyle)
            }

            // [1..notifCount] active notification icons (most-recent first), placed after the clock and
            // truncated by the layout before they reach the right cluster or cutout — like the system bar.
            shownNotifs.forEach { n ->
                MainLineBox {
                    rememberNotifIcon(n)?.let { bmp ->
                        // Decorative for accessibility: a raw package name read aloud is noise.
                        Icon(bmp, contentDescription = null, Modifier.size(12.dp), tint = Color.White)
                    }
                }
            }

            // [notifCount+1..] active indicators, left → right (battery declared last = placed far right).
            if (f.alarm) FlagItem(R.drawable.ic_status_alarm, accent, stringResource(R.string.status_desc_alarm))
            if (f.dnd) FlagItem(R.drawable.ic_status_dnd, accent, stringResource(R.string.status_desc_dnd))
            if (f.silent) FlagItem(R.drawable.ic_status_silent, accent, stringResource(R.string.status_desc_silent))
            if (f.vibrate) FlagItem(R.drawable.ic_status_vibrate, accent, stringResource(R.string.status_desc_vibrate))
            if (f.location) FlagItem(R.drawable.ic_status_location, accent, stringResource(R.string.status_desc_location))
            if (f.nfc) FlagItem(R.drawable.ic_status_nfc, accent, stringResource(R.string.status_desc_nfc))
            if (f.dataSaver) FlagItem(R.drawable.ic_status_datasaver, accent, stringResource(R.string.status_desc_datasaver))
            if (f.bluetooth) FlagItem(R.drawable.ic_status_bluetooth, accent, stringResource(R.string.status_desc_bluetooth))
            if (f.vpn) FlagItem(R.drawable.ic_status_vpn, accent, stringResource(R.string.status_desc_vpn))
            if (f.airplane) FlagItem(R.drawable.ic_status_airplane, accent, stringResource(R.string.status_desc_airplane))

            // Mobile signal (hidden in airplane mode): bars with the generation (5G/4G/LTE) stacked under.
            if (s.mobile.active && !f.airplane) {
                StatusItem(sub = s.mobile.generation, subColor = colors.signalColor(s.mobile.level)) {
                    SignalBars(level = s.mobile.level, activeColor = colors.signalColor(s.mobile.level))
                }
            }

            // Wi-Fi: a glyph tinted by signal strength, with the band (2.4 / 5 / 6 GHz) stacked under it —
            // same two-part layout as the mobile signal's generation. No sub-label when the band is unknown.
            if (s.wifi.connected) {
                val band = when (s.wifi.band) {
                    WifiBand.GHZ_2_4 -> stringResource(R.string.status_wifi_band_24)
                    WifiBand.GHZ_5 -> stringResource(R.string.status_wifi_band_5)
                    WifiBand.GHZ_6 -> stringResource(R.string.status_wifi_band_6)
                    WifiBand.UNKNOWN -> null
                }
                StatusItem(sub = band, subColor = colors.signalColor(s.wifi.level)) {
                    Icon(
                        painterResource(R.drawable.ic_status_wifi),
                        stringResource(R.string.status_desc_wifi),
                        Modifier.size(14.dp),
                        tint = colors.signalColor(s.wifi.level),
                    )
                }
            }

            // Battery (always, far right): bar + charging bolt, with the percentage stacked underneath.
            StatusItem(sub = stringResource(R.string.status_battery_percent, s.battery.percent), subColor = batteryColor) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    if (s.battery.charging) {
                        Icon(
                            painterResource(R.drawable.ic_status_charging),
                            stringResource(R.string.status_desc_charging),
                            Modifier.size(10.dp),
                            tint = batteryColor,
                        )
                    }
                    BatteryBar(percent = s.battery.percent, color = batteryColor)
                }
            }
        },
    ) { measurables, constraints ->
        val edge = STATUS_EDGE_PAD.roundToPx()
        val gap = STATUS_GAP.roundToPx()
        val notifGap = STATUS_NOTIF_GAP.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val contentH = placeables.maxOf { it.height }
        val clock = placeables[0]
        val notifIcons = placeables.subList(1, 1 + notifCount)
        val indicators = placeables.subList(1 + notifCount, placeables.size)
        val cut = cutout
        val cutLeft = cut?.left ?: -1
        val cutRight = cut?.right ?: -1
        // Drop the primary line so its centre sits level with the camera cutout's vertical centre.
        // cutout + barTopInWindow are both window-space, so subtracting the bar's own top makes this
        // exact wherever the bar ends up (different status-bar heights across devices).
        val topY = if (alignToCutout && cut != null) {
            ((cut.top + cut.bottom) / 2 - barTopInWindow - STATUS_MAIN_LINE.roundToPx() / 2).coerceAtLeast(0)
        } else {
            0
        }

        layout(width, topY + contentH) {
            // Clock at the left edge, but pushed to the right of the cutout if it would land under one
            // (e.g. a top-LEFT corner camera) — so the clock is never hidden behind the hole.
            var clockX = edge
            if (cut != null && clockX < cutRight && clockX + clock.width > cutLeft) {
                clockX = cutRight + gap // small breathing gap past the cutout
            }
            clock.placeRelative(clockX, topY)

            // Indicators fill from the right edge inward; jump over the cutout when a glyph would land
            // under it (e.g. a top-RIGHT or centre camera) and continue on the cutout's left side.
            // Battery is rightmost (declared last), the least-important flags leftmost, so if a narrow
            // screen runs out of room the loop drops the leftmost flags rather than overlapping the
            // clock. The floor never triggers on normal-width screens (indicators don't reach that far
            // left), so wide layouts are unchanged — this only degrades gracefully when truly cramped.
            val clockRight = clockX + clock.width + gap
            var x = width - edge
            for (p in indicators.asReversed()) {
                var left = x - p.width
                if (cut != null && left < cutRight && x > cutLeft) {
                    x = cutLeft - gap
                    left = x - p.width
                }
                if (left < clockRight) break // would collide with the clock — drop the rest (leftmost flags)
                p.placeRelative(left, topY)
                x = left - gap
            }
            val rightClusterLeft = x // leftmost edge the right cluster reached

            // Notification icons grow right from the clock, but STOP before they'd collide with the right
            // cluster or the cutout — so a flood of notifications truncates cleanly instead of overlapping.
            var nx = clockX + clock.width + notifGap
            for (p in notifIcons) {
                if (p.width == 0) continue // icon failed to load — skip its empty slot
                if (cut != null && nx < cutRight && nx + p.width > cutLeft) nx = cutRight + gap
                if (nx + p.width > rightClusterLeft) break // out of room — drop the rest
                p.placeRelative(nx, topY)
                nx += p.width + notifGap
            }
        }
    }
    }
    }
}

/** A primary glyph centred on the shared main line (so battery bar, Wi-Fi, signal and flags align). */
@Composable
private fun MainLineBox(content: @Composable () -> Unit) {
    Box(Modifier.height(STATUS_MAIN_LINE), contentAlignment = Alignment.Center) { content() }
}

/** A two-part indicator: the primary glyph on the main line, a small [sub] label stacked beneath it. */
@Composable
private fun StatusItem(sub: String?, subColor: Color, primary: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MainLineBox(primary)
        if (sub != null) {
            Text(
                sub,
                color = subColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 9.sp,
                style = StatusTextStyle,
            )
        }
    }
}

/** A single binary status-flag icon on the main line. */
@Composable
private fun FlagItem(@DrawableRes res: Int, tint: Color, desc: String) {
    MainLineBox {
        Icon(painterResource(res), desc, Modifier.size(13.dp), tint = tint)
    }
}

/** Rasterises a notification's small icon (an [android.graphics.drawable.Icon]) into a tintable bitmap;
 *  null on any failure so it is simply skipped. */
@Composable
private fun rememberNotifIcon(n: StatusNotification): ImageBitmap? {
    val context = LocalContext.current
    return remember(n.key, n.postTime) {
        runCatching {
            n.icon.loadDrawable(context)?.toBitmap(width = 42, height = 42)?.asImageBitmap()
        }.getOrNull()
    }
}

/** Current time, formatted per the device's 12/24h setting, refreshed periodically. */
@Composable
private fun rememberClock(): String {
    val context = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(context) }
    var time by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            time = fmt.format(Date())
            delay(15_000)
        }
    }
    return time
}
