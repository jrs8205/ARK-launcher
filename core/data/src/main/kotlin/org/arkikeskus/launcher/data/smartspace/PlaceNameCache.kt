package org.arkikeskus.launcher.data.smartspace

/** A resolved place name and whether it came from the network fallback. */
data class PlaceName(val name: String, val fromNetwork: Boolean)

/**
 * The weather's place name: the device's own geocoder first, then (only when the user opted in) the
 * network fallback. The last name is kept for its rounded area so a transient geocoder failure does
 * not blank a name the user was already seeing — but a clearly different area must not keep the
 * previous town, a dead geocoder must not pin a stale name forever, and a name that came from the
 * network is dropped as soon as the fallback is turned off. Not thread-safe: the weather refresh is
 * its single caller.
 */
internal class PlaceNameCache(
    private val maxAgeMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var name: String? = null
    private var areaKey: String? = null
    private var atMs = 0L
    private var fromNetwork = false

    /** [network] is null while the network fallback is turned off; it is never called then. */
    fun resolve(areaKey: String, local: () -> String?, network: (() -> String?)?): PlaceName? {
        local()?.let { return remember(it, areaKey, fromNetwork = false) }
        network?.invoke()?.let { return remember(it, areaKey, fromNetwork = true) }
        val alive = now() - atMs < maxAgeMs
        val allowed = network != null || !fromNetwork
        val cached = name
        return if (cached != null && areaKey == this.areaKey && alive && allowed) PlaceName(cached, fromNetwork) else null
    }

    private fun remember(name: String, areaKey: String, fromNetwork: Boolean): PlaceName {
        this.name = name
        this.areaKey = areaKey
        this.atMs = now()
        this.fromNetwork = fromNetwork
        return PlaceName(name, fromNetwork)
    }
}
