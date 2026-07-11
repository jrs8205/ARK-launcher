package org.arkikeskus.launcher.data.smartspace

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.di.ApplicationScope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Current conditions for the smartspace widget's weather slot. */
data class CurrentWeather(
    val temperatureC: Double,
    val weatherCode: Int,
    /** Reverse-geocoded locality ("Espoo"); null when no geocoder backend or no name resolves. */
    val city: String?,
    val fetchedAt: Long,
)

/**
 * Current weather from the keyless Open-Meteo API for the smartspace widget. Needs a (coarse)
 * location permission; without one it emits null and never touches the network. Results are cached
 * in memory and re-fetched at most every [REFRESH_INTERVAL_MS] (the widget refreshes on home resume).
 */
@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _weather = MutableStateFlow<CurrentWeather?>(null)
    val weather: StateFlow<CurrentWeather?> = _weather.asStateFlow()

    private val fetching = AtomicBoolean(false)

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (!hasPermission()) {
            _weather.value = null
            return
        }
        val cached = _weather.value
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < REFRESH_INTERVAL_MS) return
        if (!fetching.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val location = lastKnownLocation() ?: return@launch
                // Rounded for the network query — ~1 km is all weather needs, and the exact fix
                // never leaves the device. The LOCAL geocoder gets the exact fix (rounding cost
                // real municipalities near borders); see cityName().
                val exactLat = location.latitude
                val exactLon = location.longitude
                val lat = Math.round(exactLat * 100) / 100.0
                val lon = Math.round(exactLon * 100) / 100.0
                runCatching { fetch(lat, lon) }.getOrNull()?.let {
                    _weather.value = it.copy(city = resolveCity(exactLat, exactLon, lat, lon))
                }
            } finally {
                fetching.set(false)
            }
        }
    }

    /** A cached fix is plenty for weather — no active location request, no battery cost. */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        // Newest fix wins regardless of provider — provider order alone let a stale network fix
        // beat a fresh GPS one and pin the weather to a previous town.
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun fetch(lat: Double, lon: Double): CurrentWeather? {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code"
                .format(Locale.US, lat, lon),
        )
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            CurrentWeather(
                temperatureC = current.getDouble("temperature_2m"),
                weatherCode = current.getInt("weather_code"),
                city = null,
                fetchedAt = System.currentTimeMillis(),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Last successfully geocoded name + the rounded area it belongs to: a transient geocoder
     *  failure must not blank a name the user was already seeing, but a clearly different area
     *  must not keep showing the previous town. Guarded by the [fetching] gate (single writer). */
    private var lastCity: String? = null
    private var lastCityAreaKey: String? = null
    private var lastCityAtMs = 0L

    private fun resolveCity(exactLat: Double, exactLon: Double, roundedLat: Double, roundedLon: Double): String? {
        val areaKey = "%.2f,%.2f".format(Locale.US, roundedLat, roundedLon)
        val fresh = cityName(exactLat, exactLon)
        if (fresh != null) {
            lastCity = fresh
            lastCityAreaKey = areaKey
            lastCityAtMs = System.currentTimeMillis()
            return fresh
        }
        // Same rounded area AND recent enough: a transient failure must not blank the name, but a
        // dead geocoder must not pin a stale name forever either.
        val cacheAlive = System.currentTimeMillis() - lastCityAtMs < CITY_CACHE_MAX_AGE_MS
        return if (areaKey == lastCityAreaKey && cacheAlive) lastCity else null
    }

    /** Locality via the platform Geocoder — works worldwide on devices with a geocoder backend
     *  (localized names); null elsewhere. Checks several results and fields: older OEM backends
     *  (e.g. Samsung A40) may leave locality empty and put the usable name in another field.
     *  The sync call is fine on this IO thread. Logs never include coordinates. */
    private fun cityName(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) {
            Log.d(TAG, "Reverse geocoding skipped: no geocoder backend")
            return null
        }
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 3).orEmpty()
            if (addresses.isEmpty()) {
                Log.d(TAG, "Reverse geocoding returned no addresses")
                return null
            }
            val name = addresses.firstNotNullOfOrNull { a ->
                PlaceNames.preferred(listOf(a.locality, a.subLocality, a.subAdminArea, a.adminArea, a.featureName))
            }
            if (name == null) Log.d(TAG, "Reverse geocoding found no usable name field")
            name
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocoding failed: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30L * 60 * 1000
        private const val CITY_CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val TAG = "WeatherRepo"
    }
}
