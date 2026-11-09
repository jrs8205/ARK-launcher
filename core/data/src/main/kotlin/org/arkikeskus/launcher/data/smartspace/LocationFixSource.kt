package org.arkikeskus.launcher.data.smartspace

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** A cached fix older than this is a different town by now (same horizon as the weather reading
 *  itself): it is refreshed before use, and shown only when the refresh yields nothing. */
internal const val LOCATION_MAX_AGE_MS = 3L * 60 * 60 * 1000

/** How long one current-location request may take before the source gives up on it. */
internal const val CURRENT_LOCATION_TIMEOUT_MS = 20_000L

/**
 * The position the weather is fetched for. A recent fix some app already obtained is used as is
 * (no battery cost); when nothing usable is cached, ONE coarse current-location request is made.
 * Reading cached fixes alone is not enough: on a device where no other app keeps a fix warm (no
 * Google services refreshing the fused provider in the background), the cache stays empty and
 * the weather row would never appear at all. The caller holds the location permission.
 */
internal class LocationFixSource(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = CURRENT_LOCATION_TIMEOUT_MS,
) {
    @SuppressLint("MissingPermission")
    suspend fun fix(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val cached = lastKnownLocation(lm)
        // A clock set back makes the fix look future-dated; that never counts as stale.
        if (cached != null && now() - cached.time <= LOCATION_MAX_AGE_MS) return cached
        for (provider in requestProviders(lm)) {
            requestCurrentLocation(lm, provider)?.let { return it }
        }
        if (cached == null) Log.d(TAG, "No location fix available") else Log.d(TAG, "Current location unavailable, using the cached fix")
        return cached
    }

    /** The newest fix any app has obtained, regardless of provider — provider order alone let a
     *  stale network fix beat a fresh GPS one and pin the weather to a previous town. */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(lm: LocationManager): Location? {
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** The providers a coarse-permission app may ask for a current location, enabled ones only:
     *  fused (Android 12+) first, then the network provider. GPS is never requested — a coarse
     *  permission does not allow it, and weather does not need it. */
    private fun requestProviders(lm: LocationManager): List<String> {
        val all = runCatching { lm.allProviders }.getOrDefault(emptyList())
        return buildList {
            if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in all) add(LocationManager.FUSED_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in all) add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** One current-location request, cancelled when it exceeds [timeoutMs] or the caller leaves. */
    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocation(lm: LocationManager, provider: String): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    lm.getCurrentLocation(provider, signal, directExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Current location request failed: ${e.javaClass.simpleName}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private val directExecutor = Executor { it.run() }

    private companion object {
        const val TAG = "LocationFix"
    }
}
