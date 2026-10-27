package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Weather fetched in the morning is not the weather of the evening: when every later fetch fails
 * (offline, roaming without data, no location fix) the old reading is dropped after a few hours
 * instead of standing as the current conditions all day.
 */
class WeatherAgeTest {
    private val fetchedAt = 1_800_000_000_000L
    private val weather = CurrentWeather(temperatureC = 12.0, weatherCode = 1, city = "Espoo", fetchedAt = fetchedAt)

    @Test fun aRecentReadingIsKept() {
        assertThat(weatherExpired(weather, fetchedAt + 2 * 60 * 60 * 1000L)).isFalse()
    }

    @Test fun aReadingOlderThanTheMaximumAgeIsDropped() {
        assertThat(weatherExpired(weather, fetchedAt + WEATHER_MAX_AGE_MS + 1)).isTrue()
    }

    @Test fun aClockSetBackKeepsTheReading() {
        assertThat(weatherExpired(weather, fetchedAt - 10 * 60 * 60 * 1000L)).isFalse()
    }
}
