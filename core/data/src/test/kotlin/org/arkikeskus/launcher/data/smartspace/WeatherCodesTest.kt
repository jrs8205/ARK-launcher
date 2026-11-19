package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WeatherCodesTest {

    @Test
    fun clear_sky_is_sun() {
        assertThat(WeatherCodes.emoji(0)).isEqualTo("☀️")
    }

    @Test
    fun few_clouds_are_sun_behind_cloud() {
        assertThat(WeatherCodes.emoji(1)).isEqualTo("🌤️")
        assertThat(WeatherCodes.emoji(2)).isEqualTo("🌤️")
    }

    @Test
    fun overcast_is_cloud() {
        assertThat(WeatherCodes.emoji(3)).isEqualTo("☁️")
    }

    @Test
    fun fog_codes_are_fog() {
        assertThat(WeatherCodes.emoji(45)).isEqualTo("🌫️")
        assertThat(WeatherCodes.emoji(48)).isEqualTo("🌫️")
    }

    @Test
    fun drizzle_rain_and_showers_are_rain() {
        for (code in intArrayOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)) {
            assertThat(WeatherCodes.emoji(code)).isEqualTo("🌧️")
        }
    }

    @Test
    fun snow_codes_are_snow() {
        for (code in intArrayOf(71, 73, 75, 77, 85, 86)) {
            assertThat(WeatherCodes.emoji(code)).isEqualTo("🌨️")
        }
    }

    @Test
    fun thunderstorm_codes_are_thunder() {
        for (code in intArrayOf(95, 96, 99)) {
            assertThat(WeatherCodes.emoji(code)).isEqualTo("⛈️")
        }
    }

    @Test
    fun unknown_codes_fall_back_to_cloud() {
        assertThat(WeatherCodes.emoji(42)).isEqualTo("☁️")
        assertThat(WeatherCodes.emoji(-1)).isEqualTo("☁️")
    }

    /** The drawn icons go by the kind of weather, the same grouping as the emoji. */
    @Test
    fun every_code_has_a_kind_matching_its_emoji() {
        assertThat(WeatherCodes.kind(0)).isEqualTo(WeatherKind.CLEAR)
        assertThat(WeatherCodes.kind(2)).isEqualTo(WeatherKind.PARTLY_CLOUDY)
        assertThat(WeatherCodes.kind(3)).isEqualTo(WeatherKind.CLOUDY)
        assertThat(WeatherCodes.kind(48)).isEqualTo(WeatherKind.FOG)
        assertThat(WeatherCodes.kind(63)).isEqualTo(WeatherKind.RAIN)
        assertThat(WeatherCodes.kind(81)).isEqualTo(WeatherKind.RAIN)
        assertThat(WeatherCodes.kind(75)).isEqualTo(WeatherKind.SNOW)
        assertThat(WeatherCodes.kind(96)).isEqualTo(WeatherKind.THUNDER)
        assertThat(WeatherCodes.kind(1234)).isEqualTo(WeatherKind.CLOUDY)
        for (code in 0..99) {
            val expected = when (WeatherCodes.kind(code)) {
                WeatherKind.CLEAR -> "☀️"
                WeatherKind.PARTLY_CLOUDY -> "🌤️"
                WeatherKind.FOG -> "🌫️"
                WeatherKind.RAIN -> "🌧️"
                WeatherKind.SNOW -> "🌨️"
                WeatherKind.THUNDER -> "⛈️"
                WeatherKind.CLOUDY -> "☁️"
            }
            assertThat(WeatherCodes.emoji(code)).isEqualTo(expected)
        }
    }

    /** By night a clear or mostly clear sky is a moon, not the sun (the owner's request, 2026-11-19). */
    @Test
    fun a_clear_night_is_a_moon() {
        assertThat(WeatherCodes.emoji(0, isDay = false)).isEqualTo("🌙")
        assertThat(WeatherCodes.emoji(1, isDay = false)).isEqualTo("🌙")
        assertThat(WeatherCodes.emoji(2, isDay = false)).isEqualTo("🌙")
    }

    /** Cloud, fog, rain, snow and thunder have no sun in them: they are the same by night. */
    @Test
    fun the_rest_of_the_night_is_as_by_day() {
        for (code in intArrayOf(3, 45, 48, 55, 61, 71, 82, 95, 99, 1234)) {
            assertThat(WeatherCodes.emoji(code, isDay = false)).isEqualTo(WeatherCodes.emoji(code))
        }
    }
}
