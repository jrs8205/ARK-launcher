package org.arkikeskus.launcher.data.smartspace

import org.json.JSONObject

/** Pure field selection for the BigDataCloud reverse-geocode response (JVM-testable). */
object PlaceNameFallback {
    fun parseCity(json: String): String? = runCatching {
        val o = JSONObject(json)
        PlaceNames.preferred(listOf(o.optString("city"), o.optString("locality"), o.optString("principalSubdivision")))
    }.getOrNull()
}
