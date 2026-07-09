package org.arkikeskus.launcher.data.smartspace

/** One calendar event instance, times in epoch millis (all-day instances already shifted to local). */
data class CalendarEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
)

/**
 * Picks the single event the smartspace widget shows: the ongoing or next-starting instance,
 * preferring timed events over all-day ones (an all-day banner would otherwise mask a real
 * meeting all day). Pure and JVM-testable; the 7-day lookahead is enforced by the query window.
 */
object NextEventPicker {
    fun pick(events: List<CalendarEvent>, now: Long): CalendarEvent? {
        val live = events.filter { it.end > now }
        val (allDay, timed) = live.partition { it.allDay }
        return bestOf(timed, now) ?: bestOf(allDay, now)
    }

    /** Ongoing (ending soonest) wins over upcoming (starting soonest). */
    private fun bestOf(events: List<CalendarEvent>, now: Long): CalendarEvent? =
        events.filter { it.begin <= now }.minByOrNull { it.end }
            ?: events.filter { it.begin > now }.minByOrNull { it.begin }
}
