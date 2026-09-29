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
    fun `next delivery is the first time later today, else tomorrow`() {
        val times = BatchSchedule.parse("08:00,12:00,17:00")
        assertThat(BatchSchedule.nextDelivery(at(2026, 9, 29, 9, 30), times, zone)).isEqualTo(at(2026, 9, 29, 12, 0))
        assertThat(BatchSchedule.nextDelivery(at(2026, 9, 29, 17, 0), times, zone)).isEqualTo(at(2026, 9, 30, 8, 0))
        assertThat(BatchSchedule.nextDelivery(at(2026, 9, 29, 9, 30), emptyList(), zone)).isNull()
    }
}
