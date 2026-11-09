package org.arkikeskus.launcher.data.smartspace

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The weather's position. A device where no other app keeps a location fix warm (no Google
 * services refreshing the fused provider in the background) has nothing cached, and a launcher
 * that only READS cached fixes then never shows any weather at all — the report from Spain
 * (Oukitel, Android 15): location on, internet on, clean install, empty weather row. So the
 * source asks for ONE coarse current location when nothing usable is cached, and a stale fix
 * from hours ago (a different town by now) is refreshed the same way but still stands in when
 * the request yields nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocationFixSourceTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val lm: LocationManager = context.getSystemService(LocationManager::class.java)
    private val now = 1_800_000_000_000L

    @Before fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(lm).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
    }

    /** A fix whose realtime age matches its wall-clock age: like the platform, the shadow hands a
     *  current-location request the cached fix straight back when that age is under 10 s. */
    private fun fix(provider: String, lat: Double, time: Long) = Location(provider).apply {
        latitude = lat
        longitude = 24.0
        this.time = time
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - (now - time) * 1_000_000
    }

    private fun source(timeoutMs: Long = 20_000L) = LocationFixSource(context, now = { now }, timeoutMs = timeoutMs)

    @Test fun aRecentCachedFixIsUsedWithoutARequest() = runTest {
        val cached = fix(LocationManager.FUSED_PROVIDER, 60.2, now - 5 * 60 * 1000L)
        shadowOf(lm).setLastKnownLocation(LocationManager.FUSED_PROVIDER, cached)

        val result = source().fix()

        assertThat(result?.latitude).isEqualTo(60.2)
        assertThat(shadowOf(lm).locationUpdateListeners).isEmpty()
    }

    @Test fun withNothingCachedACurrentLocationIsRequested() = runTest {
        val pending = async { source().fix() }
        runCurrent()

        shadowOf(lm).simulateLocation(fix(LocationManager.FUSED_PROVIDER, 40.4, now))

        assertThat(pending.await()?.latitude).isEqualTo(40.4)
    }

    @Test fun aStaleCachedFixIsRefreshed() = runTest {
        shadowOf(lm).setLastKnownLocation(
            LocationManager.FUSED_PROVIDER, fix(LocationManager.FUSED_PROVIDER, 60.2, now - 4 * 60 * 60 * 1000L),
        )
        val pending = async { source().fix() }
        runCurrent()

        shadowOf(lm).simulateLocation(fix(LocationManager.FUSED_PROVIDER, 40.4, now))

        assertThat(pending.await()?.latitude).isEqualTo(40.4)
    }

    @Test fun aStaleCachedFixStandsInWhenTheRequestYieldsNothing() = runTest {
        shadowOf(lm).setLastKnownLocation(
            LocationManager.FUSED_PROVIDER, fix(LocationManager.FUSED_PROVIDER, 60.2, now - 4 * 60 * 60 * 1000L),
        )
        val pending = async { source(timeoutMs = 1_000L).fix() }
        runCurrent()

        advanceUntilIdle()

        assertThat(pending.await()?.latitude).isEqualTo(60.2)
    }

    @Test fun noUsableProviderGivesNullWithoutARequest() = runTest {
        shadowOf(lm).setProviderEnabled(LocationManager.FUSED_PROVIDER, false)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        val result = source(timeoutMs = 1_000L).fix()

        assertThat(result).isNull()
        assertThat(shadowOf(lm).locationUpdateListeners).isEmpty()
    }

    @Test fun theNetworkProviderServesWhenThereIsNoFusedOne() = runTest {
        shadowOf(lm).removeProvider(LocationManager.FUSED_PROVIDER)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val pending = async { source().fix() }
        runCurrent()

        shadowOf(lm).simulateLocation(fix(LocationManager.NETWORK_PROVIDER, 40.4, now))

        assertThat(pending.await()?.latitude).isEqualTo(40.4)
    }
}
