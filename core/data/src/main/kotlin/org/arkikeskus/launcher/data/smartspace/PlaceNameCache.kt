package org.arkikeskus.launcher.data.smartspace

import android.os.SystemClock
import java.util.Locale

/** A resolved place name and whether it came from the network fallback. */
data class PlaceName(val name: String, val fromNetwork: Boolean)

/**
 * The weather's place name: the device's own geocoder first, then (only when the user opted in) the
 * network fallback. In the same rounded area a cached name lives for [maxAgeMs]. On the move it
 * stands in for at most [networkIntervalMs] from the original reading. Turning off the fallback
 * drops a network name. The area key carries the UI language
 * ([placeAreaKey]): a name resolved in one language is never shown after the user switches the
 * app or system language. Not thread-safe: the weather refresh is its single caller.
 */
internal class PlaceNameCache(
    private val maxAgeMs: Long,
    /** The least time between two questions put to the network, answered or not. */
    private val networkIntervalMs: Long = NETWORK_PLACE_NAME_INTERVAL_MS,
    /**
     * The clock both lengths of time are counted on (the name's age and the wait between two questions to the
     * network): the time since boot, which only goes forward. Not the wall clock: these are lengths of time
     * inside one process, kept nowhere, and a wall clock that was set back a day made the next question to the
     * network a day and a half hour away, with no name to show meanwhile after a change of language.
     */
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    /** When the network was last asked; null = never. */
    private var networkAskedAtMs: Long? = null

    private var name: String? = null
    private var areaKey: String? = null
    private var atMs = 0L
    private var fromNetwork = false

    /** [network] is null while the network fallback is turned off; it is never called then. */
    fun resolve(areaKey: String, local: () -> String?, network: (() -> String?)?): PlaceName? {
        if (fromNetwork && network == null) name = null
        local()?.let { return remember(it, areaKey, fromNetwork = false) }
        // By its own half hour, not the weather's quarter: the privacy statement promises the network is asked
        // at most once in thirty minutes. In between the last name stands (below).
        if (network != null && networkDue()) {
            networkAskedAtMs = now()
            network()?.let { return remember(it, areaKey, fromNetwork = true) }
        }
        val cached = name ?: return null
        val previous = this.areaKey ?: return null
        if (previous.substringAfter('@') != areaKey.substringAfter('@')) return null
        val lifetime = if (areaKey == previous) maxAgeMs else minOf(maxAgeMs, networkIntervalMs)
        return if (now() - atMs < lifetime) PlaceName(cached, fromNetwork) else null
    }

    private fun networkDue(): Boolean {
        val asked = networkAskedAtMs ?: return true
        return now() - asked >= networkIntervalMs
    }

    private fun remember(name: String, areaKey: String, fromNetwork: Boolean): PlaceName {
        this.name = name
        this.areaKey = areaKey
        this.atMs = now()
        this.fromNetwork = fromNetwork
        return PlaceName(name, fromNetwork)
    }
}

/** How often the network is asked for a place name at most: the half hour the privacy statement names. The
 *  weather itself is renewed more often. */
internal const val NETWORK_PLACE_NAME_INTERVAL_MS = 30L * 60 * 1000

/** The cache key for a rounded location in one UI language: a language switch (Android 13+ app
 *  language or the system language) must re-geocode instead of reusing "London" on a Finnish screen. */
internal fun placeAreaKey(roundedLat: Double, roundedLon: Double, languageTag: String): String =
    "%.2f,%.2f@%s".format(Locale.US, roundedLat, roundedLon, languageTag)
