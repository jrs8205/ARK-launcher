package org.arkikeskus.launcher.feature.home

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.smartspace.CalendarEvent
import org.arkikeskus.launcher.data.smartspace.CalendarRepository
import org.arkikeskus.launcher.data.smartspace.NextEventPicker
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SmartspaceViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
) : ViewModel() {

    val hasCalendarPermission = MutableStateFlow(calendarRepository.hasPermission())

    /** Re-checks the permission (resume / after the runtime grant) and re-queries the provider. */
    fun refresh() {
        hasCalendarPermission.value = calendarRepository.hasPermission()
        calendarRepository.refresh()
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

    init {
        refresh()
    }
}

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
    val context = LocalContext.current
    val hasPermission by viewModel.hasCalendarPermission.collectAsStateWithLifecycle()
    val nextEvent by viewModel.nextEvent.collectAsStateWithLifecycle()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val timeFormat = remember(now / 3_600_000) { android.text.format.DateFormat.getTimeFormat(context) }
    val timeText = remember(now) { timeFormat.format(Date(now)) }
    val dateText = remember(now) {
        DateUtils.formatDateTime(
            context, now,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
        )
    }

    // The wallpaper is arbitrary, so the text carries its own soft shadow (like the home labels).
    val shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 8f)
    // Taps must not show a ripple box over the wallpaper — the widget has no background surface.
    val noIndication = remember { MutableInteractionSource() }

    // Centered like the Pixel lock-screen clock — the widget is seeded full-width, so centering
    // the content centers it on the screen (user feedback: left-aligned looked off). The card hugs
    // the content instead of filling the whole grid footprint, so a tall span doesn't read as a
    // giant empty panel. The TEXT scales with the footprint (user feedback: resizing must visibly
    // grow the widget): height drives the scale, width caps it so a narrow span can't overflow.
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    val scale = minOf(maxHeight / 190.dp, maxWidth / 220.dp).coerceIn(0.75f, 1.8f)
    Column(
        modifier = Modifier
            // A soft translucent card behind the text so the widget reads as one element on any
            // wallpaper (user feedback; same idiom as the dock background and the restore tiles).
            .background(Color.Black.copy(alpha = 0.30f), RoundedCornerShape((24 * scale).dp))
            .padding(horizontal = (22 * scale).dp, vertical = (10 * scale).dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = timeText,
            color = Color.White,
            style = TextStyle(fontSize = (46 * scale).sp, fontWeight = FontWeight.Medium, shadow = shadow),
            maxLines = 1,
            modifier = Modifier.clickable(interactionSource = noIndication, indication = null) {
                runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            },
        )
        Text(
            text = dateText,
            color = Color.White,
            style = TextStyle(fontSize = (15 * scale).sp, shadow = shadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(interactionSource = noIndication, indication = null) {
                val uri = CalendarContract.CONTENT_URI.buildUpon()
                    .appendPath("time").appendPath(now.toString()).build()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            },
        )
        // The next alarm (AlarmManager, no permission) gets its OWN row under the date (user choice).
        val nextAlarm = remember(now) {
            context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        }
        if (nextAlarm != null) {
            val alarmText = remember(nextAlarm) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(interactionSource = noIndication, indication = null) {
                        runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
                    },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_status_alarm),
                    contentDescription = stringResource(R.string.smartspace_next_alarm),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size((15 * scale).dp),
                )
                Spacer(Modifier.width((4 * scale).dp))
                Text(
                    text = alarmText,
                    color = Color.White.copy(alpha = 0.9f),
                    style = TextStyle(fontSize = (14 * scale).sp, shadow = shadow),
                    maxLines = 1,
                )
            }
        }
        val event = nextEvent
        when {
            !hasPermission -> Text(
                text = stringResource(R.string.smartspace_allow_calendar),
                color = Color.White.copy(alpha = 0.85f),
                style = TextStyle(fontSize = (14 * scale).sp, shadow = shadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(interactionSource = noIndication, indication = null) {
                        permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    },
            )
            event != null -> {
                val eventText = remember(event, now) {
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
                Text(
                    text = eventText.ifBlank { stringResource(R.string.smartspace_no_title) },
                    color = Color.White.copy(alpha = 0.92f),
                    style = TextStyle(fontSize = (14 * scale).sp, shadow = shadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable(interactionSource = noIndication, indication = null) {
                            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, uri)
                                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end),
                                )
                            }
                        },
                )
            }
            // No permission prompt needed and no event → clock + date only.
        }
    }
    }
}
