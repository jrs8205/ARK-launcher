package org.arkikeskus.launcher.feature.home

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.smartspace.CalendarEvent
import org.arkikeskus.launcher.data.smartspace.CalendarRepository
import org.arkikeskus.launcher.data.smartspace.CurrentWeather
import org.arkikeskus.launcher.data.smartspace.NextEventPicker
import org.arkikeskus.launcher.data.smartspace.WeatherCodes
import org.arkikeskus.launcher.data.smartspace.WeatherRepository
import org.arkikeskus.launcher.model.AppItem
import kotlin.math.roundToInt
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SmartspaceViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val weatherRepository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val appRepository: AppRepository,
) : ViewModel() {

    val hasCalendarPermission = MutableStateFlow(calendarRepository.hasPermission())
    val hasLocationPermission = MutableStateFlow(weatherRepository.hasPermission())

    /** Weather slot enabled in Settings ▸ Home (the permission gate is separate). */
    val showWeather: StateFlow<Boolean> = settingsRepository.settings
        .map { it.showWeather }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // A place name from the network fallback hides the moment the setting goes off, before any
    // refresh: the repository drops it only when the home screen next resumes.
    val weather: StateFlow<CurrentWeather?> =
        combine(weatherRepository.weather, settingsRepository.settings) { w, s ->
            when {
                !s.showWeather -> null
                w != null && w.cityFromNetwork && !s.networkPlaceNames -> w.copy(city = null, cityFromNetwork = false)
                else -> w
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-checks the permissions (resume / after a runtime grant) and re-queries the sources. */
    fun refresh() {
        hasCalendarPermission.value = calendarRepository.hasPermission()
        hasLocationPermission.value = weatherRepository.hasPermission()
        calendarRepository.refresh()
        // Gate on the PERSISTED setting, not the StateFlow: at process start the stateIn initial
        // value (true) would fire one location read + network query even with weather turned off.
        viewModelScope.launch {
            val s = settingsRepository.settings.first()
            if (s.showWeather) weatherRepository.refresh(s.networkPlaceNames)
        }
    }

    /** Re-picked every minute so an ended event flips to the next one without a provider change. */
    val nextEvent: StateFlow<CalendarEvent?> =
        combine(calendarRepository.events, minuteTicks()) { events, now -> NextEventPicker.pick(events, now) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun minuteTicks(): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(60_000 - now % 60_000)
        }
    }

    /** The app a tap on the weather line opens (Settings > Home), only while it is installed. */
    val weatherApp: StateFlow<AppItem?> =
        combine(settingsRepository.settings, appRepository.apps) { s, apps -> weatherAppIn(apps, s.weatherAppKey) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Opens the chosen weather app; a no-op when none is chosen or it is no longer installed. */
    fun openWeatherApp() {
        viewModelScope.launch {
            val key = settingsRepository.settings.first().weatherAppKey
            weatherAppIn(appRepository.apps.first(), key)?.let { app ->
                appRepository.launch(app).onFailure { Log.w("SmartspaceViewModel", "Failed to launch ${app.key}", it) }
            }
        }
    }

    init {
        refresh()
    }
}

/** The app [key] names when it is among [apps]; a blank or unknown key resolves to nothing. */
internal fun weatherAppIn(apps: List<AppItem>, key: String): AppItem? =
    if (key.isBlank()) null else apps.firstOrNull { it.key == key }

/** What the smartspace shows, read from [SmartspaceViewModel] (or built directly by a test). */
internal data class SmartspaceState(
    val hasCalendarPermission: Boolean,
    val nextEvent: CalendarEvent?,
    val hasLocationPermission: Boolean,
    val showWeather: Boolean,
    val weather: CurrentWeather?,
    val weatherApp: AppItem?,
)

/**
 * The built-in smartspace widget: a large clock (12/24 h from the SYSTEM setting), the localized
 * date, and the ongoing-or-next calendar event within 7 days. Taps open the system clock app, the
 * calendar's day view, and the event itself. The v2 weather slot goes beside the date row.
 */
@Composable
fun SmartspaceWidget(
    modifier: Modifier = Modifier,
    viewModel: SmartspaceViewModel = hiltViewModel(),
) {
    val state = SmartspaceState(
        hasCalendarPermission = viewModel.hasCalendarPermission.collectAsStateWithLifecycle().value,
        nextEvent = viewModel.nextEvent.collectAsStateWithLifecycle().value,
        hasLocationPermission = viewModel.hasLocationPermission.collectAsStateWithLifecycle().value,
        showWeather = viewModel.showWeather.collectAsStateWithLifecycle().value,
        weather = viewModel.weather.collectAsStateWithLifecycle().value,
        weatherApp = viewModel.weatherApp.collectAsStateWithLifecycle().value,
    )
    SmartspaceContent(state, onRefresh = viewModel::refresh, onOpenWeatherApp = viewModel::openWeatherApp, modifier = modifier)
}

/**
 * The widget's layout. [onRefresh] re-reads the sources on resume and after a permission grant;
 * [onOpenWeatherApp] runs when the weather line is tapped, and the line is tappable only while
 * [SmartspaceState.weatherApp] resolves (no chosen app = plain text, no click action).
 */
@Composable
internal fun SmartspaceContent(
    state: SmartspaceState,
    onRefresh: () -> Unit,
    onOpenWeatherApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasPermission = state.hasCalendarPermission
    val nextEvent = state.nextEvent
    val hasLocationPermission = state.hasLocationPermission
    val showWeather = state.showWeather
    val weather = state.weather
    val weatherApp = state.weatherApp

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onRefresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onRefresh() }

    val timeFormat = rememberTimeFormat()
    val timeText = remember(now, timeFormat) { timeFormat.format(Date(now)) }
    val dateText = remember(now) {
        DateUtils.formatDateTime(
            context, now,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
        )
    }

    // The wallpaper is arbitrary, so the text carries its own soft shadow (like the home labels).
    val ink = widgetContentColor()
    val shadow = Shadow(color = if (LocalTonalWidgets.current) Color.Transparent else Color.Black.copy(alpha = 0.55f), blurRadius = 8f)
    // Taps must not show a ripple box over the wallpaper — the widget has no background surface.
    val noIndication = remember { MutableInteractionSource() }

    // Centered like the Pixel lock-screen clock — the widget is seeded full-width, so centering
    // the content centers it on the screen (user feedback: left-aligned looked off). The card hugs
    // the content instead of filling the whole grid footprint, so a tall span doesn't read as a
    // giant empty panel. The TEXT scales with the footprint (user feedback: resizing must visibly
    // grow the widget): height drives the scale, width caps it so a narrow span can't overflow.
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    // A short widget changes layout instead of shrinking every label into unreadable text.
    if (maxHeight < 140.dp) {
        // Its two lines share the footprint's height left after the card padding; each is a touch
        // target up to TOUCH_TARGET tall when there is room (see the taller layouts below).
        val lineMin = ((maxHeight - 24.dp) / 2).coerceIn(0.dp, TOUCH_TARGET)
        WidgetSurface(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The clock's hit area spans both lines.
                TapTarget(minHeight = 0.dp, fillWidth = false, ripple = true, modifier = Modifier.fillMaxHeight(), onTap = {
                    runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
                }) {
                    Text(timeText, color = ink, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                }
                Column(Modifier.weight(1f)) {
                    TapTarget(minHeight = lineMin, alignment = Alignment.CenterStart, ripple = true, onTap = {
                        val uri = CalendarContract.CONTENT_URI.buildUpon()
                            .appendPath("time").appendPath(now.toString()).build()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) {
                        Text(dateText, color = ink, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val event = nextEvent
                    val detail = when {
                        !hasPermission -> stringResource(R.string.smartspace_allow_calendar)
                        event != null -> event.title.ifBlank { stringResource(R.string.smartspace_no_title) }
                        weather != null -> weather.temperatureC.roundToInt().toString() + "° " + WeatherCodes.emoji(weather.weatherCode)
                        else -> null
                    }
                    // Only a line that does something gets a click action: a disabled clickable
                    // would still announce itself as a (disabled) control.
                    val detailTap: (() -> Unit)? = when {
                        !hasPermission -> ({ permissionLauncher.launch(Manifest.permission.READ_CALENDAR) })
                        event != null -> ({
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
                                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end))
                            }
                        })
                        weather != null && weatherApp != null -> onOpenWeatherApp
                        else -> null
                    }
                    if (detail != null) TapTarget(minHeight = lineMin, alignment = Alignment.CenterStart, ripple = true, onTap = detailTap) {
                        Text(detail, color = ink.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        return@BoxWithConstraints
    }
    val scale = if (maxHeight >= 260.dp && maxWidth >= 300.dp) 1.15f else 0.9f
    val spacious = maxHeight >= 240.dp
    val expanded = maxHeight >= 260.dp
    val w = weather
    val event = nextEvent
    val nextAlarm = remember(now) {
        context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime
    }
    val alarmText = if (nextAlarm != null) {
        remember(nextAlarm, timeFormat) {
            buildString {
                if (!DateUtils.isToday(nextAlarm)) {
                    append(
                        DateUtils.formatDateTime(
                            context, nextAlarm,
                            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
                        ),
                    )
                    append(' ')
                }
                append(timeFormat.format(Date(nextAlarm)))
            }
        }
    } else {
        null
    }
    val openCalendarDay = {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time").appendPath(now.toString()).build()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        Unit
    }
    val openAlarms = {
        runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        Unit
    }
    // The weather line opens the chosen app only while that app resolves; otherwise it is plain text.
    val openWeather: (() -> Unit)? = if (weatherApp != null) onOpenWeatherApp else null
    // The bottom line (the calendar event, or its tap-to-grant prompt) is what the compact mode
    // makes room for: with it, weather + date + alarm share ONE middle row so the widget
    // tops out at three rows; without it, they keep their own airy rows (user design).
    val bottomLine = !hasPermission || event != null
    val merged = !spacious && bottomLine
    // Weather is on but can't run without a location — a tap-to-grant line. Independent of the
    // calendar line: each launches its own dialog on tap, so nothing ever stacks two system
    // prompts; a user who declines calendar must still be able to enable weather from here.
    val locationLine = showWeather && !hasLocationPermission
    // Touch targets (WCAG 2.5.5): the lines under the clock share the footprint's height left
    // after the clock and the card padding, each growing to TOUCH_TARGET at most. A tight footprint
    // keeps today's compact pitch; a tall one gets lines a finger can hit without aiming.
    val lineCount = (if (merged) 1 else (if (w != null) 1 else 0) + 1 + (if (alarmText != null) 1 else 0)) +
        (if (bottomLine) 1 else 0) + (if (locationLine) 1 else 0)
    // The clock's real height once laid out (it grows with the system font size); the estimate only
    // covers the first frame. The 2 dp gaps above the lower lines come off the budget as well, so the
    // last line is never the one that pays for a short estimate.
    val density = LocalDensity.current
    var clockHeightPx by remember { mutableIntStateOf(0) }
    val clockHeight = if (clockHeightPx > 0) with(density) { clockHeightPx.toDp() } else (46 * scale * 1.3f * density.fontScale).dp
    val gaps = 2.dp * ((if (!merged && alarmText != null) 1 else 0) + (if (bottomLine) 1 else 0) + (if (locationLine) 1 else 0))
    val lineMin = ((maxHeight - clockHeight - (20 * scale).dp - gaps) / lineCount).coerceIn(0.dp, TOUCH_TARGET)
    Column(
        modifier = Modifier
            // The lines fill the card's width so their hit areas do too; IntrinsicSize.Max keeps
            // the card hugging its widest line instead of the whole footprint.
            .width(IntrinsicSize.Max)
            // A soft translucent card behind the text so the widget reads as one element on any
            // wallpaper (user feedback; same idiom as the dock background and the restore tiles).
            .background(widgetSurfaceColor(), RoundedCornerShape(24.dp))
            .padding(horizontal = (22 * scale).dp, vertical = (10 * scale).dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TapTarget(
            minHeight = 0.dp,
            onTap = openAlarms,
            interactionSource = noIndication,
            modifier = Modifier.onSizeChanged { clockHeightPx = it.height },
        ) {
            Text(
                text = timeText,
                color = widgetContentColor(),
                style = TextStyle(fontSize = (46 * scale).sp, fontWeight = FontWeight.Medium, shadow = shadow),
                maxLines = 1,
            )
        }
        if (merged) {
            Row(Modifier.heightIn(min = lineMin), verticalAlignment = Alignment.CenterVertically) {
                if (w != null) {
                    val city = w.city?.let { " $it" }.orEmpty()
                    // The city is the variable-length part — it alone gives way (ellipsis) when
                    // the merged row runs out of width; fill=false keeps the card content-hugging.
                    TapTarget(minHeight = lineMin, onTap = openWeather, fillWidth = false, interactionSource = noIndication,
                        modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "${w.temperatureC.roundToInt()}° ${WeatherCodes.emoji(w.weatherCode)}$city",
                            color = widgetContentColor(),
                            style = TextStyle(fontSize = (15 * scale).sp, shadow = shadow),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MiddleDot(scale, shadow)
                }
                TapTarget(minHeight = lineMin, onTap = openCalendarDay, fillWidth = false, interactionSource = noIndication) {
                    Text(
                        text = dateText,
                        color = widgetContentColor(),
                        style = TextStyle(fontSize = (15 * scale).sp, shadow = shadow),
                        maxLines = 1,
                    )
                }
                if (alarmText != null) {
                    MiddleDot(scale, shadow)
                    TapTarget(minHeight = lineMin, onTap = openAlarms, fillWidth = false, interactionSource = noIndication) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_status_alarm),
                                contentDescription = stringResource(R.string.smartspace_next_alarm),
                                tint = widgetContentColor().copy(alpha = 0.9f),
                                modifier = Modifier.size((14 * scale).dp),
                            )
                            Spacer(Modifier.width((3 * scale).dp))
                            Text(
                                text = alarmText,
                                color = widgetContentColor().copy(alpha = 0.9f),
                                style = TextStyle(fontSize = (15 * scale).sp, shadow = shadow),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        } else {
            // Weather right under the clock on its own row (user choice — more prominent than
            // riding on the date line).
            if (w != null) {
                val city = w.city?.let { " $it" }.orEmpty()
                TapTarget(minHeight = lineMin, onTap = openWeather, interactionSource = noIndication) {
                    Text(
                        text = "${w.temperatureC.roundToInt()}° ${WeatherCodes.emoji(w.weatherCode)}$city",
                        color = widgetContentColor(),
                        style = TextStyle(fontSize = (16 * scale).sp, shadow = shadow),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TapTarget(minHeight = lineMin, onTap = openCalendarDay, interactionSource = noIndication) {
                Text(
                    text = dateText,
                    color = widgetContentColor(),
                    style = TextStyle(fontSize = (15 * scale).sp, shadow = shadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The next alarm (AlarmManager, no permission) gets its OWN row under the date.
            if (alarmText != null) {
                TapTarget(minHeight = lineMin, onTap = openAlarms, interactionSource = noIndication, modifier = Modifier.padding(top = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_status_alarm),
                            contentDescription = stringResource(R.string.smartspace_next_alarm),
                            tint = widgetContentColor().copy(alpha = 0.9f),
                            modifier = Modifier.size((15 * scale).dp),
                        )
                        Spacer(Modifier.width((4 * scale).dp))
                        Text(
                            text = alarmText,
                            color = widgetContentColor().copy(alpha = 0.9f),
                            style = TextStyle(fontSize = (14 * scale).sp, shadow = shadow),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        when {
            !hasPermission -> TapTarget(
                minHeight = lineMin,
                onTap = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
                interactionSource = noIndication,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.smartspace_allow_calendar),
                    color = widgetContentColor().copy(alpha = 0.85f),
                    style = TextStyle(fontSize = (14 * scale).sp, shadow = shadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            event != null -> {
                val eventText = remember(event, now, timeFormat) {
                    buildString {
                        if (!DateUtils.isToday(event.begin)) {
                            append(
                                DateUtils.formatDateTime(
                                    context, event.begin,
                                    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
                                ),
                            )
                            append(' ')
                        }
                        if (!event.allDay) {
                            append(timeFormat.format(Date(event.begin)))
                            append(' ')
                        }
                        append(event.title)
                    }
                }
                TapTarget(
                    minHeight = lineMin,
                    onTap = {
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end),
                            )
                        }
                    },
                    interactionSource = noIndication,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        text = eventText.ifBlank { stringResource(R.string.smartspace_no_title) },
                        color = widgetContentColor().copy(alpha = 0.92f),
                        style = TextStyle(fontSize = (14 * scale).sp, shadow = shadow),
                        maxLines = if (expanded) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // No permission prompt needed and no event → clock + date only.
        }
        if (locationLine) {
            val locationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { onRefresh() }
            TapTarget(
                minHeight = lineMin,
                onTap = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                interactionSource = noIndication,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.smartspace_allow_location),
                    color = widgetContentColor().copy(alpha = 0.85f),
                    style = TextStyle(fontSize = (14 * scale).sp, shadow = shadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}

/** WCAG 2.5.5 target size: a tappable line grows to at least this height when the footprint has room. */
internal val TOUCH_TARGET = 44.dp

/**
 * A tappable line of the widget. Its hit area is at least [minHeight] tall with the content
 * centred ([alignment]) in it and, with [fillWidth], as wide as the card, so a small label is as
 * easy to hit as a button. Without [onTap] it is plain content with no click action. [ripple]
 * keeps the default indication (the short layout on its surface); the wallpaper layouts pass their
 * shared no-indication [interactionSource] instead.
 */
@Composable
private fun TapTarget(
    minHeight: Dp,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
    alignment: Alignment = Alignment.Center,
    ripple: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = minHeight)
            .then(
                when {
                    onTap == null -> Modifier
                    ripple -> Modifier.clickable(onClick = onTap)
                    else -> Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onTap)
                },
            ),
        contentAlignment = alignment,
    ) { content() }
}

/** The separator between the merged middle-row parts (compact mode). */
@Composable
private fun MiddleDot(scale: Float, shadow: Shadow) {
    Text(
        text = "·",
        color = widgetContentColor().copy(alpha = 0.8f),
        style = TextStyle(fontSize = (15 * scale).sp, shadow = shadow),
        modifier = Modifier.padding(horizontal = (5 * scale).dp),
    )
}
