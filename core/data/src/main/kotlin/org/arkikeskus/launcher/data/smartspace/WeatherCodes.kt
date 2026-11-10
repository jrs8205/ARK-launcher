package org.arkikeskus.launcher.data.smartspace

/** The kinds of weather the widget tells apart (one icon each). */
enum class WeatherKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

/** Maps a WMO weather code (Open-Meteo `current.weather_code`) to a compact display glyph. */
object WeatherCodes {
    /** The kind of weather a code stands for; an unknown code is plain cloud, like its emoji. */
    fun kind(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.CLEAR
        1, 2 -> WeatherKind.PARTLY_CLOUDY
        45, 48 -> WeatherKind.FOG
        in 51..57, in 61..67, in 80..82 -> WeatherKind.RAIN
        in 71..77, 85, 86 -> WeatherKind.SNOW
        in 95..99 -> WeatherKind.THUNDER
        else -> WeatherKind.CLOUDY
    }

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
