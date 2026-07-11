package org.arkikeskus.launcher.data.smartspace

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.arkikeskus.launcher.data.di.ApplicationScope
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** How far ahead the smartspace widget looks for the next calendar event. */
private const val LOOKAHEAD_MS = 7L * 24 * 60 * 60 * 1000

/** Upper bound on loaded instances PER BUCKET (timed / all-day) — the picker only ever needs the
 *  front of the window, and bucketing keeps a wall of all-day rows from starving a real meeting. */
private const val MAX_INSTANCES = 50

/**
 * Calendar-event instances for the smartspace widget: the next [LOOKAHEAD_MS] window from the
 * calendar provider, re-queried when the calendar data changes (ContentObserver) or [refresh] is
 * called (resume / permission grant). Emits an empty list while READ_CALENDAR isn't granted.
 */
@Singleton
class CalendarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private var observing = false

    /** Serializes refreshes: ContentObserver bursts + resume/grant refreshes would otherwise run
     *  concurrent jobs whose out-of-order completions could leave [_events] a stale snapshot. */
    private val refreshMutex = Mutex()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            refreshMutex.withLock {
                if (!hasPermission()) {
                    _events.value = emptyList() // a revoked permission really does clear the data
                    return@withLock
                }
                // Register lazily on the first permitted refresh — registering without the permission
                // would be pointless (no data) and the observer lives for the process lifetime anyway.
                // Flag only after the platform call succeeded, or a transient failure would skip
                // registration forever.
                if (!observing) {
                    runCatching {
                        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
                    }.onSuccess { observing = true }
                }
                // A failed query keeps the previous good events (a transient provider error must not
                // blank an event the user was already seeing); the next change/resume retries anyway.
                runCatching { query() }.onSuccess { _events.value = it }
            }
        }
    }

    private fun query(): List<CalendarEvent> =
        queryInstances(allDay = false) + queryInstances(allDay = true)

    private fun queryInstances(allDay: Boolean): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath((now + LOOKAHEAD_MS).toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        // Bucketed by ALL_DAY so a window-front full of all-day instances can never truncate a
        // timed event out of the load before NextEventPicker prioritizes in memory.
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "${CalendarContract.Instances.ALL_DAY} = ${if (allDay) 1 else 0} AND " +
            "(${CalendarContract.Instances.STATUS} IS NULL OR " +
            "${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"
        val list = ArrayList<CalendarEvent>()
        context.contentResolver.query(
            uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            val zone = TimeZone.getDefault()
            while (c.moveToNext() && list.size < MAX_INSTANCES) {
                val isAllDay = c.getInt(4) == 1
                var begin = c.getLong(2)
                var end = c.getLong(3)
                // All-day instances are stored as UTC midnights; shift to LOCAL midnights so the
                // picker's ongoing/upcoming decisions and the rendered day are the user's day.
                if (isAllDay) {
                    begin -= zone.getOffset(begin)
                    end -= zone.getOffset(end)
                }
                list += CalendarEvent(c.getLong(0), c.getString(1).orEmpty(), begin, end, isAllDay)
            }
        }
        return list
    }
}
