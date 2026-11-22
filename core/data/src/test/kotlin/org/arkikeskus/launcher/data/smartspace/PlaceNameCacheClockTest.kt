package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Review round 12. The half hour between two questions put to the network is a length of time inside the
 * process, and it was counted on the wall clock: with the phone's clock set back a day, the next question was a
 * day and a half hour away, and after a change of language (when the old name cannot be shown) a phone whose
 * own geocoder does not answer had no place name for all that time. The cache counts on the time since boot,
 * which only goes forward. Here the phone's time since boot is moved on and the wall clock is not: the cache is
 * made as the weather makes it, with its own clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceNameCacheClockTest {
    @Test fun theHalfHourBetweenQuestionsToTheNetworkIsCountedOnTheTimeSinceBoot() {
        val cache = PlaceNameCache(maxAgeMs = 24 * 60 * 60_000L)
        var asked = 0
        val network: () -> String? = { asked++; null }

        cache.resolve("60.21,24.87@fi", local = { null }, network = network)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(29))
        cache.resolve("60.21,24.87@en", local = { null }, network = network)
        assertThat(asked).isEqualTo(1)

        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        cache.resolve("60.21,24.87@en", local = { null }, network = network)
        assertThat(asked).isEqualTo(2)
    }
}
