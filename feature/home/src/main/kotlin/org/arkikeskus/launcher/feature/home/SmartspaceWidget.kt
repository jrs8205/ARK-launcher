package org.arkikeskus.launcher.feature.home

import android.Manifest
import android.content.ContentUris
import android.content.Intent
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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
import org.arkikeskus.launcher.model.LauncherSettings
import kotlin.math.roundToInt
import java.util.Date
import javax.inject.Inject
import org.arkikeskus.launcher.ui.themedTextStyle

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
    val look = LocalSmartspaceStyle.current
    // What is behind the widget depends on where it sits on the glass panel (the panel's tint fades
    // downwards): its centre in the window, known once it is laid out.
    val backdrop = LocalHomeBackdrop.current
    var centerY by remember { mutableStateOf<Float?>(null) }
    val ink = smartspaceInk(look, widgetContentColor(), backdrop.colorBehind(centerY))
    val surface = smartspaceSurface(look, widgetSurfaceColor(), LocalTonalWidgets.current)
    // No shadow under dark text (the theme's surface, a light card of the user's).
    val shadow = Shadow(
        color = if (LocalTonalWidgets.current || ink == Color.Black) Color.Transparent else Color.Black.copy(alpha = 0.55f),
        blurRadius = 8f,
    )
    // Taps must not show a ripple box over the wallpaper — the widget has no background surface.
    val noIndication = remember { MutableInteractionSource() }

    // Centered like the Pixel lock-screen clock — the widget is seeded full-width, so centering
    // the content centers it on the screen (user feedback: left-aligned looked off). The card hugs
    // the content instead of filling the whole grid footprint, so a tall span doesn't read as a
    // giant empty panel. The TEXT scales with the footprint (user feedback: resizing must visibly
    // grow the widget): height drives the scale, width caps it so a narrow span can't overflow.
    BoxWithConstraints(
        // Only the panel's tint depends on the place: without the panel nothing is tracked.
        modifier = if (backdrop.glassOpacity == null) modifier else modifier.onGloballyPositioned { centerY = it.boundsInRoot().center.y },
        contentAlignment = Alignment.Center,
    ) {
    // A short widget changes layout instead of shrinking every label into unreadable text.
    if (maxHeight < 140.dp) {
        // Its two lines share the footprint's height left after the card padding; each is a touch
        // target up to TOUCH_TARGET tall when there is room (see the taller layouts below).
        val lineMin = ((maxHeight - 24.dp) / 2).coerceIn(0.dp, TOUCH_TARGET)
        WidgetSurface(Modifier.fillMaxWidth(), color = surface, contentColor = ink) {
            Row(Modifier.padding(12.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The clock's hit area spans both lines.
                TapTarget(minHeight = 0.dp, fillWidth = false, ripple = true, modifier = Modifier.fillMaxHeight(), onTap = {
                    openClockApp(context)
                }) {
                    Text(timeText, color = ink, style = MaterialTheme.typography.headlineMedium.scaledBy(look.textScale), maxLines = 1,
                        modifier = Modifier.testTag(SMARTSPACE_CLOCK_TAG))
                }
                Column(Modifier.weight(1f)) {
                    TapTarget(minHeight = lineMin, alignment = Alignment.CenterStart, ripple = true, onTap = {
                        val uri = CalendarContract.CONTENT_URI.buildUpon()
                            .appendPath("time").appendPath(now.toString()).build()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) {
                        Text(dateText, color = ink, style = MaterialTheme.typography.bodySmall.scaledBy(look.textScale),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val event = nextEvent
                    val detail = when {
                        !hasPermission -> stringResource(R.string.smartspace_allow_calendar)
                        event != null -> event.title.ifBlank { stringResource(R.string.smartspace_no_title) }
                        weather != null -> "" // drawn by WeatherLine below
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
                        val detailStyle = MaterialTheme.typography.bodySmall.scaledBy(look.textScale)
                        if (hasPermission && event == null && weather != null) {
                            WeatherLine(weather, withCity = false, iconStyle = look.weatherIcon, color = ink.copy(alpha = 0.85f), textStyle = detailStyle)
                        } else {
                            Text(detail, color = ink.copy(alpha = 0.85f), style = detailStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        return@BoxWithConstraints
    }
    val scale = (if (maxHeight >= 260.dp && maxWidth >= 300.dp) 1.15f else 0.9f) * look.textScale
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
        openClockApp(context)
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
    // keeps today's compact pitch; a tall one gets lines a finger can hit without aiming. The lines
    // are measured by [BudgetedLines], never by a Column: a Column hands each line what the lines
    // above it left, so touch areas grown to 44 dp cut a two-line event to one line.
    BudgetedLines(
        modifier = Modifier
            // The lines fill the card's width so their hit areas do too; IntrinsicSize.Max keeps
            // the card hugging its widest line instead of the whole footprint.
            .width(IntrinsicSize.Max)
            // A soft translucent card behind the text so the widget reads as one element on any
            // wallpaper (user feedback; same idiom as the dock background and the restore tiles).
            .background(surface, RoundedCornerShape(24.dp))
            .padding(horizontal = (22 * scale).dp, vertical = (10 * scale).dp),
    ) {
        TapTarget(onTap = openAlarms, interactionSource = noIndication) {
            Text(
                text = timeText,
                color = ink,
                style = themedTextStyle(TextStyle(fontSize = (46 * scale).sp, fontWeight = FontWeight.Medium, shadow = shadow)),
                maxLines = 1,
                modifier = Modifier.testTag(SMARTSPACE_CLOCK_TAG),
            )
        }
        if (merged) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (w != null) {
                    // The city is the variable-length part — it alone gives way (ellipsis) when
                    // the merged row runs out of width; fill=false keeps the card content-hugging.
                    TapTarget(onTap = openWeather, fillWidth = false, fillHeight = true, interactionSource = noIndication,
                        modifier = Modifier.weight(1f, fill = false)) {
                        WeatherLine(
                            w, withCity = true, iconStyle = look.weatherIcon, color = ink,
                            textStyle = themedTextStyle(TextStyle(fontSize = (15 * scale).sp, shadow = shadow)),
                        )
                    }
                    MiddleDot(scale, shadow, ink)
                }
                TapTarget(onTap = openCalendarDay, fillWidth = false, fillHeight = true, interactionSource = noIndication) {
                    Text(
                        text = dateText,
                        color = ink,
                        style = themedTextStyle(TextStyle(fontSize = (15 * scale).sp, shadow = shadow)),
                        maxLines = 1,
                    )
                }
                if (alarmText != null) {
                    MiddleDot(scale, shadow, ink)
                    TapTarget(onTap = openAlarms, fillWidth = false, fillHeight = true, interactionSource = noIndication) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_status_alarm),
                                contentDescription = stringResource(R.string.smartspace_next_alarm),
                                tint = ink.copy(alpha = 0.9f),
                                modifier = Modifier.size((14 * scale).dp),
                            )
                            Spacer(Modifier.width((3 * scale).dp))
                            Text(
                                text = alarmText,
                                color = ink.copy(alpha = 0.9f),
                                style = themedTextStyle(TextStyle(fontSize = (15 * scale).sp, shadow = shadow)),
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
                TapTarget(onTap = openWeather, interactionSource = noIndication) {
                    WeatherLine(
                        w, withCity = true, iconStyle = look.weatherIcon, color = ink,
                        textStyle = themedTextStyle(TextStyle(fontSize = (16 * scale).sp, shadow = shadow)),
                    )
                }
            }
            TapTarget(onTap = openCalendarDay, interactionSource = noIndication) {
                Text(
                    text = dateText,
                    color = ink,
                    style = themedTextStyle(TextStyle(fontSize = (15 * scale).sp, shadow = shadow)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The next alarm (AlarmManager, no permission) gets its OWN row under the date.
            if (alarmText != null) {
                TapTarget(onTap = openAlarms, interactionSource = noIndication, modifier = Modifier.lineGap(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_status_alarm),
                            contentDescription = stringResource(R.string.smartspace_next_alarm),
                            tint = ink.copy(alpha = 0.9f),
                            modifier = Modifier.size((15 * scale).dp),
                        )
                        Spacer(Modifier.width((4 * scale).dp))
                        Text(
                            text = alarmText,
                            color = ink.copy(alpha = 0.9f),
                            style = themedTextStyle(TextStyle(fontSize = (14 * scale).sp, shadow = shadow)),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        when {
            !hasPermission -> TapTarget(
                onTap = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
                interactionSource = noIndication,
                modifier = Modifier.lineGap(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.smartspace_allow_calendar),
                    color = ink.copy(alpha = 0.85f),
                    style = themedTextStyle(TextStyle(fontSize = (14 * scale).sp, shadow = shadow)),
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
                    modifier = Modifier.lineGap(2.dp),
                ) {
                    Text(
                        text = eventText.ifBlank { stringResource(R.string.smartspace_no_title) },
                        color = ink.copy(alpha = 0.92f),
                        style = themedTextStyle(TextStyle(fontSize = (14 * scale).sp, shadow = shadow)),
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
                onTap = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                interactionSource = noIndication,
                modifier = Modifier.lineGap(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.smartspace_allow_location),
                    color = ink.copy(alpha = 0.85f),
                    style = themedTextStyle(TextStyle(fontSize = (14 * scale).sp, shadow = shadow)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}

/** [this] with its font size and line height multiplied by [scale] (the widget's text-size setting). */
private fun TextStyle.scaledBy(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

/**
 * The weather line: the temperature, the weather's icon and, [withCity], the place. With the standard
 * icon it is the one text it has always been (the emoji in the text); every other style draws the icon
 * beside the temperature ([WeatherGlyph]), and the place alone gives way when the line runs out of width.
 */
@Composable
private fun WeatherLine(w: CurrentWeather, withCity: Boolean, iconStyle: String, color: Color, textStyle: TextStyle) {
    val city = if (withCity) w.city else null
    val temperature = "${w.temperatureC.roundToInt()}°"
    if (iconStyle == LauncherSettings.WEATHER_ICON_EMOJI) {
        Text(
            text = "$temperature ${WeatherCodes.emoji(w.weatherCode)}" + city?.let { " $it" }.orEmpty(),
            color = color, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(temperature, color = color, style = textStyle, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        WeatherGlyph(w.weatherCode, iconStyle, textStyle.fontSize)
        if (city != null) {
            Spacer(Modifier.width(4.dp))
            Text(city, color = color, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** WCAG 2.5.5 target size: a tappable line grows to at least this height when the footprint has room. */
internal val TOUCH_TARGET = 44.dp

/**
 * The clock and the lines under it, stacked and centred like a Column, with the lines' heights
 * budgeted from the room the footprint leaves after the clock: every line keeps at least its
 * natural height (a two-line event stays two lines), and the room left after that raises the
 * shortest lines first, all of them to the same level at most [TOUCH_TARGET] tall. Natural heights
 * that do not fit are cut to the room left, top down, so the card never draws past its footprint.
 * The first child is the clock, always at its natural height; a line's [lineGap] is the space
 * above it, outside its hit area and outside the budget.
 */
@Composable
private fun BudgetedLines(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val childConstraints = Constraints(minWidth = constraints.minWidth, maxWidth = width)
        val clock = measurables.first().measure(childConstraints)
        val lines = measurables.drop(1)
        val gaps = lines.map { ((it.parentData as? LineGap)?.gap ?: 0.dp).roundToPx() }
        val natural = lines.map { it.minIntrinsicHeight(width) }
        val room = if (constraints.hasBoundedHeight) constraints.maxHeight - clock.height - gaps.sum() else 0
        val heights = if (constraints.hasBoundedHeight && natural.sum() > room) {
            // More content than footprint (a tight footprint with a huge font): the lines are cut to
            // the room left, top down, so nothing draws over the grid row below the widget.
            var left = room
            natural.map { h -> h.coerceAtMost(left.coerceAtLeast(0)).also { left -= it } }
        } else {
            budgetedLineHeights(natural, room, TOUCH_TARGET.roundToPx())
        }
        val placeables = lines.mapIndexed { i, line ->
            line.measure(childConstraints.copy(minHeight = heights[i], maxHeight = heights[i]))
        }
        val layoutWidth = (placeables + clock).maxOf { it.width }.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = (clock.height + gaps.sum() + heights.sum()).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(layoutWidth, layoutHeight) {
            var y = 0
            clock.placeRelative((layoutWidth - clock.width) / 2, y)
            y += clock.height
            placeables.forEachIndexed { i, p ->
                y += gaps[i]
                p.placeRelative((layoutWidth - p.width) / 2, y)
                y += p.height
            }
        }
    }
}

/**
 * The heights of lines with [natural] content heights sharing [room] px: each at least natural,
 * the leftover raising the shortest lines first to one common level, capped at [target].
 */
internal fun budgetedLineHeights(natural: List<Int>, room: Int, target: Int): List<Int> {
    if (natural.isEmpty()) return natural
    val sorted = natural.sorted()
    var level = 0
    var above = sorted.sum() // the natural heights of the lines still taller than the level
    for (k in 1..sorted.size) {
        above -= sorted[k - 1]
        val candidate = (room - above) / k
        if (candidate < sorted[k - 1]) break
        level = candidate
    }
    level = level.coerceAtMost(target)
    return natural.map { maxOf(it, level) }
}

/** The space above a line in [BudgetedLines]: outside its hit area and outside the budget. */
private fun Modifier.lineGap(gap: Dp): Modifier = this.then(LineGap(gap))

private data class LineGap(val gap: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@LineGap
}

/**
 * A tappable line of the widget. Its hit area is at least [minHeight] tall (or, with [fillHeight],
 * as tall as its parent lets it be) with the content centred ([alignment]) in it and, with
 * [fillWidth], as wide as the card, so a small label is as easy to hit as a button. Without
 * [onTap] it is plain content with no click action. [ripple] keeps the default indication (the
 * short layout on its surface); the wallpaper layouts pass their shared no-indication
 * [interactionSource] instead.
 */
@Composable
private fun TapTarget(
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    fillWidth: Boolean = true,
    fillHeight: Boolean = false,
    alignment: Alignment = Alignment.Center,
    ripple: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
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
private fun MiddleDot(scale: Float, shadow: Shadow, ink: Color) {
    Text(
        text = "·",
        color = ink.copy(alpha = 0.8f),
        style = themedTextStyle(TextStyle(fontSize = (15 * scale).sp, shadow = shadow)),
        modifier = Modifier.padding(horizontal = (5 * scale).dp),
    )
}
