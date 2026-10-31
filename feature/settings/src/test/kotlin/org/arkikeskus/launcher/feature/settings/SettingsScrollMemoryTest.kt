package org.arkikeskus.launcher.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Owner decision 2026-10-31: Settings reopened within five minutes of leaving it starts where it was
 * left, so a quick look at home and back does not mean scrolling the whole list again; later it starts
 * from the top. Process memory only, so a fresh process starts from the top too.
 */
class SettingsScrollMemoryTest {
    private val t0 = 1_000_000L
    private val minute = 60_000L

    @Before fun fresh() = SettingsScrollMemory.clear()

    @Test fun aFreshProcessStartsFromTheTop() {
        assertThat(SettingsScrollMemory.restore(now = t0)).isEqualTo(0)
    }

    @Test fun withinFiveMinutesTheListStartsWhereItWasLeft() {
        SettingsScrollMemory.remember(offset = 2345, now = t0)

        assertThat(SettingsScrollMemory.restore(now = t0 + 4 * minute)).isEqualTo(2345)
        assertThat(SettingsScrollMemory.restore(now = t0 + 5 * minute)).isEqualTo(2345)
    }

    @Test fun afterFiveMinutesItStartsFromTheTop() {
        SettingsScrollMemory.remember(offset = 2345, now = t0)

        assertThat(SettingsScrollMemory.restore(now = t0 + 5 * minute + 1)).isEqualTo(0)
    }

    @Test fun leavingAgainRestartsTheClockWithTheNewPlace() {
        SettingsScrollMemory.remember(offset = 2345, now = t0)
        SettingsScrollMemory.remember(offset = 800, now = t0 + 4 * minute)

        assertThat(SettingsScrollMemory.restore(now = t0 + 8 * minute)).isEqualTo(800)
        assertThat(SettingsScrollMemory.restore(now = t0 + 10 * minute)).isEqualTo(0)
    }

    @Test fun theWindowIsFiveMinutes() {
        assertThat(SettingsScrollMemory.WINDOW_MS).isEqualTo(5 * minute)
    }
}
