package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the weather service is asked and how its answer is read. The widget shows the weather of this moment
 * at this place: the temperature, what the sky is doing, and whether it is day there (a clear night is a moon,
 * not the sun). A reading is kept for a quarter of an hour, the service's own step for current conditions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherAnswerTest {
    private val answer = """
        {"latitude":60.17,"longitude":24.94,"current_units":{"temperature_2m":"°C"},
         "current":{"time":"2026-11-19T21:15","interval":900,"temperature_2m":-3.4,"weather_code":0,"is_day":0}}
    """.trimIndent()

    @Test fun theQuestionAsksForTheTemperatureTheSkyAndDayOrNight() {
        val url = weatherUrl(60.17, 24.94)

        assertThat(url).startsWith("https://api.open-meteo.com/v1/forecast?")
        assertThat(url).contains("latitude=60.17&longitude=24.94")
        assertThat(url).contains("current=temperature_2m,weather_code,is_day")
    }

    /** A decimal comma (the phone's language) must never reach the address. */
    @Test fun thePlaceIsWrittenWithADecimalPointInEveryLanguage() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fi-FI"))
        try {
            assertThat(weatherUrl(60.1699, -24.9384)).contains("latitude=60.17&longitude=-24.94")
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test fun aNightAnswerIsRead() {
        val weather = parseCurrentWeather(answer, now = 1_000L)

        assertThat(weather.temperatureC).isEqualTo(-3.4)
        assertThat(weather.weatherCode).isEqualTo(0)
        assertThat(weather.isDay).isFalse()
        assertThat(weather.fetchedAt).isEqualTo(1_000L)
    }

    @Test fun aDayAnswerIsRead() {
        assertThat(parseCurrentWeather(answer.replace("\"is_day\":0", "\"is_day\":1"), now = 0L).isDay).isTrue()
    }

    /** An answer without the field (an older or a cut one) is taken for day: the sun, as it always was. */
    @Test fun anAnswerThatDoesNotSayIsTakenForDay() {
        assertThat(parseCurrentWeather(answer.replace(",\"is_day\":0", ""), now = 0L).isDay).isTrue()
    }

    @Test fun aReadingIsFreshForAQuarterOfAnHour() {
        val reading = parseCurrentWeather(answer, now = 0L)

        assertThat(weatherFresh(reading, now = 14 * 60_000L)).isTrue()
        assertThat(weatherFresh(reading, now = 15 * 60_000L)).isFalse()
        // A clock set back never makes a reading stale.
        assertThat(weatherFresh(reading, now = -60_000L)).isTrue()
    }
}
