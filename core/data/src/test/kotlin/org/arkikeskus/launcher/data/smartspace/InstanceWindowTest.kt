package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * All-day instances are stored as UTC midnights and the provider compares the window against them
 * before any local shift: west of UTC today's all-day event "ended" at 19:00 (New York) or 16:00
 * (Los Angeles) local, hours before the day was over, and the smartspace dropped it. The all-day
 * query therefore starts a day earlier; the picker drops what has really passed.
 */
class InstanceWindowTest {
    private val now = 1_800_000_000_000L

    @Test fun theAllDayWindowStartsADayEarly() {
        assertThat(instanceWindowStart(now, allDay = true)).isEqualTo(now - 24 * 60 * 60 * 1000L)
    }

    @Test fun theTimedWindowStartsNow() {
        assertThat(instanceWindowStart(now, allDay = false)).isEqualTo(now)
    }
}
