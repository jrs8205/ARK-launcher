package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NextEventPickerTest {

    private val now = 1_752_000_000_000L
    private val hour = 3_600_000L

    private fun timed(id: Long, beginIn: Long, endIn: Long) =
        CalendarEvent(id, "e$id", now + beginIn, now + endIn, allDay = false)

    private fun allDay(id: Long, beginIn: Long, endIn: Long) =
        CalendarEvent(id, "a$id", now + beginIn, now + endIn, allDay = true)

    @Test
    fun empty_list_gives_null() {
        assertThat(NextEventPicker.pick(emptyList(), now)).isNull()
    }

    @Test
    fun expired_events_are_never_picked() {
        val events = listOf(timed(1, -3 * hour, -hour), allDay(2, -30 * hour, -6 * hour))
        assertThat(NextEventPicker.pick(events, now)).isNull()
    }

    @Test
    fun picks_the_earliest_upcoming_timed_event() {
        val events = listOf(timed(1, 5 * hour, 6 * hour), timed(2, 2 * hour, 3 * hour))
        assertThat(NextEventPicker.pick(events, now)?.eventId).isEqualTo(2L)
    }

    @Test
    fun ongoing_timed_event_beats_an_upcoming_one() {
        val events = listOf(timed(1, hour, 2 * hour), timed(2, -hour, hour))
        assertThat(NextEventPicker.pick(events, now)?.eventId).isEqualTo(2L)
    }

    @Test
    fun of_two_ongoing_timed_events_the_one_ending_first_wins() {
        val events = listOf(timed(1, -2 * hour, 4 * hour), timed(2, -hour, hour))
        assertThat(NextEventPicker.pick(events, now)?.eventId).isEqualTo(2L)
    }

    @Test
    fun ongoing_all_day_event_never_masks_a_timed_event() {
        val events = listOf(allDay(1, -6 * hour, 18 * hour), timed(2, 3 * hour, 4 * hour))
        assertThat(NextEventPicker.pick(events, now)?.eventId).isEqualTo(2L)
    }

    @Test
    fun ongoing_all_day_is_shown_when_nothing_else_exists() {
        val events = listOf(allDay(1, -6 * hour, 18 * hour), allDay(2, 24 * hour, 48 * hour))
        assertThat(NextEventPicker.pick(events, now)?.eventId).isEqualTo(1L)
    }

    @Test
    fun upcoming_all_day_is_the_last_resort() {
        val events = listOf(allDay(1, 48 * hour, 72 * hour), allDay(2, 24 * hour, 48 * hour))
        assertThat(NextEventPicker.pick(events, now)?.eventId).isEqualTo(2L)
    }
}
