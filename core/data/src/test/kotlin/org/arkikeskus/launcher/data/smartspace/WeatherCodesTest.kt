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
}
