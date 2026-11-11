package org.arkikeskus.launcher.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Owner decision 2026-10-31: Settings reopened within five minutes of leaving it starts where it was
 * left, so a quick look at home and back does not mean finding the place again; later it starts from the
 * top of the main page. With a page per category the place is the page and how far it was scrolled.
 * Process memory only, so a fresh process starts from the top too.
 */
class SettingsScrollMemoryTest {
    private val t0 = 1_000_000L
    private val minute = 60_000L

    @Before fun fresh() = SettingsScrollMemory.clear()

    private fun place(now: Long) = SettingsScrollMemory.restore(now).let { it.page to it.offset }

    @Test fun aFreshProcessStartsAtTheTopOfTheMainPage() {
        assertThat(place(now = t0)).isEqualTo(null to 0)
    }

    @Test fun withinFiveMinutesSettingsStartsOnThePageAndAtThePlaceItWasLeft() {
        SettingsScrollMemory.remember(page = "dock", offset = 2345, now = t0)

        assertThat(place(now = t0 + 4 * minute)).isEqualTo("dock" to 2345)
        assertThat(place(now = t0 + 5 * minute)).isEqualTo("dock" to 2345)
    }

    @Test fun theMainPagesPlaceIsKeptTheSameWay() {
        SettingsScrollMemory.remember(page = null, offset = 300, now = t0)

        assertThat(place(now = t0 + minute)).isEqualTo(null to 300)
    }

    @Test fun afterFiveMinutesItStartsAtTheTopOfTheMainPage() {
        SettingsScrollMemory.remember(page = "dock", offset = 2345, now = t0)

        assertThat(place(now = t0 + 5 * minute + 1)).isEqualTo(null to 0)
    }

    @Test fun leavingAgainRestartsTheClockWithTheNewPlace() {
        SettingsScrollMemory.remember(page = "dock", offset = 2345, now = t0)
        SettingsScrollMemory.remember(page = "icons", offset = 800, now = t0 + 4 * minute)

        assertThat(place(now = t0 + 8 * minute)).isEqualTo("icons" to 800)
        assertThat(place(now = t0 + 10 * minute)).isEqualTo(null to 0)
    }

    @Test fun theWindowIsFiveMinutes() {
        assertThat(SettingsScrollMemory.WINDOW_MS).isEqualTo(5 * minute)
    }
}
