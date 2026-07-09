package org.arkikeskus.launcher.data.smartspace

/** Maps a WMO weather code (Open-Meteo `current.weather_code`) to a compact display glyph. */
object WeatherCodes {
    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        45, 48 -> "🌫️"
        in 51..57, in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "☁️"
    }
}
