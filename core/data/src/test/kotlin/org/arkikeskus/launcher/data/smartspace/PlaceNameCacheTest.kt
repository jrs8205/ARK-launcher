package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaceNameCacheTest {
    private var now = 0L
    private val cache = PlaceNameCache(maxAgeMs = 1_000) { now }
    private var networkCalls = 0
    private fun network(name: String?): () -> String? = { networkCalls++; name }

    @Test fun theNetworkIsNeverAskedWhenTheFallbackIsOff() {
        assertThat(cache.resolve("60.21,24.87", local = { null }, network = null)).isNull()
    }

    @Test fun aLocalNameNeedsNoNetwork() {
        assertThat(cache.resolve("60.21,24.87", local = { "Vantaa" }, network = network("Helsinki")))
            .isEqualTo("Vantaa")
        assertThat(networkCalls).isEqualTo(0)
    }

    @Test fun theNetworkNamesThePlaceWhenTheFallbackIsOnAndTheDeviceCannot() {
        assertThat(cache.resolve("60.21,24.87", local = { null }, network = network("Vantaa")))
            .isEqualTo("Vantaa")
    }

    @Test fun aNetworkNameIsNotReusedOnceTheFallbackIsTurnedOff() {
        cache.resolve("60.21,24.87", local = { null }, network = network("Vantaa"))

        assertThat(cache.resolve("60.21,24.87", local = { null }, network = null)).isNull()
    }

    @Test fun aLocalNameSurvivesATransientFailureInTheSameArea() {
        cache.resolve("60.21,24.87", local = { "Vantaa" }, network = null)

        assertThat(cache.resolve("60.21,24.87", local = { null }, network = null)).isEqualTo("Vantaa")
    }

    @Test fun anotherAreaDoesNotKeepThePreviousName() {
        cache.resolve("60.21,24.87", local = { "Vantaa" }, network = null)

        assertThat(cache.resolve("61.50,23.76", local = { null }, network = null)).isNull()
    }

    @Test fun anOldNameExpires() {
        cache.resolve("60.21,24.87", local = { "Vantaa" }, network = null)
        now = 1_000

        assertThat(cache.resolve("60.21,24.87", local = { null }, network = null)).isNull()
    }
}
