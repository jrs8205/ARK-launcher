package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BatchScheduleTest {

    private val zone = ZoneId.of("Europe/Helsinki")
    private fun at(y: Int, mo: Int, d: Int, h: Int, m: Int) =
        LocalDateTime.of(y, mo, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `parse accepts several separators and drops junk`() {
        assertThat(BatchSchedule.parse("17:00, 8.30 ;12:00 x 25:00 08:30")).containsExactly(510, 720, 1020).inOrder()
        assertThat(BatchSchedule.parse("")).isEmpty()
    }

    @Test
    fun `format round-trips`() {
        assertThat(BatchSchedule.format(BatchSchedule.parse("8:05,17:00"))).isEqualTo("08:05,17:00")
    }

    @Test
    fun `delivery times are wall-clock times on DST change days too`() {
        val times = BatchSchedule.parse("08:00")
        // Spring forward (29.3.2026, 03:00 → 04:00) and fall back (25.10.2026, 04:00 → 03:00).
        assertThat(BatchSchedule.nextDelivery(at(2026, 3, 29, 1, 0), times, zone)).isEqualTo(at(2026, 3, 29, 8, 0))
        assertThat(BatchSchedule.nextDelivery(at(2026, 10, 25, 1, 0), times, zone)).isEqualTo(at(2026, 10, 25, 8, 0))
        // A time inside the spring gap lands after the gap instead of an hour off.
        assertThat(BatchSchedule.nextDelivery(at(2026, 3, 29, 1, 0), BatchSchedule.parse("03:30"), zone))
            .isEqualTo(at(2026, 3, 29, 4, 30))
        // …and a later HH:mm can then be the earlier instant; the earliest instant wins.
        assertThat(BatchSchedule.nextDelivery(at(2026, 3, 29, 1, 0), BatchSchedule.parse("03:30,04:00"), zone))
            .isEqualTo(at(2026, 3, 29, 4, 0))
    }

    @Test
    fun `next delivery is the first time later today, else tomorrow`() {
        val times = BatchSchedule.parse("08:00,12:00,17:00")
        assertThat(BatchSchedule.nextDelivery(at(2026, 9, 29, 9, 30), times, zone)).isEqualTo(at(2026, 9, 29, 12, 0))
        assertThat(BatchSchedule.nextDelivery(at(2026, 9, 29, 17, 0), times, zone)).isEqualTo(at(2026, 9, 30, 8, 0))
        assertThat(BatchSchedule.nextDelivery(at(2026, 9, 29, 9, 30), emptyList(), zone)).isNull()
    }
}
