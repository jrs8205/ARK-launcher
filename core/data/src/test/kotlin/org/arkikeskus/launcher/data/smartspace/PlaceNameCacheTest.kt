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
            .isEqualTo(PlaceName("Vantaa", fromNetwork = false))
        assertThat(networkCalls).isEqualTo(0)
    }

    @Test fun theNetworkNamesThePlaceWhenTheFallbackIsOnAndTheDeviceCannot() {
        assertThat(cache.resolve("60.21,24.87", local = { null }, network = network("Vantaa")))
            .isEqualTo(PlaceName("Vantaa", fromNetwork = true))
    }

    @Test fun aCachedNetworkNameStaysMarkedAsFromTheNetwork() {
        cache.resolve("60.21,24.87", local = { null }, network = network("Vantaa"))

        assertThat(cache.resolve("60.21,24.87", local = { null }, network = network(null)))
            .isEqualTo(PlaceName("Vantaa", fromNetwork = true))
    }

    @Test fun aNetworkNameIsNotReusedOnceTheFallbackIsTurnedOff() {
        cache.resolve("60.21,24.87", local = { null }, network = network("Vantaa"))

        assertThat(cache.resolve("60.21,24.87", local = { null }, network = null)).isNull()
    }

    @Test fun aLocalNameSurvivesATransientFailureInTheSameArea() {
        cache.resolve("60.21,24.87", local = { "Vantaa" }, network = null)

        assertThat(cache.resolve("60.21,24.87", local = { null }, network = null)?.name).isEqualTo("Vantaa")
    }

    @Test fun aLocalNameTravelsForOneIntervalFromItsResolution() {
        val moving = PlaceNameCache(maxAgeMs = 1_000, networkIntervalMs = 100) { now }
        moving.resolve("60,24@fi", { "Vantaa" }, null)
        now = 99
        assertThat(moving.resolve("61,25@fi", { null }, network(null))).isEqualTo(PlaceName("Vantaa", false))
        now = 100
        assertThat(moving.resolve("62,25@fi", { null }, network(null))).isNull()
        assertThat(moving.resolve("60,24@fi", { null }, null)).isEqualTo(PlaceName("Vantaa", false))
    }

    @Test fun theNetworkNameTravelsBetweenQuestionsInItsOwnLanguageWhileAllowed() {
        cache.resolve("60,24@fi", { null }, network("Vantaa"))
        now = 100
        assertThat(cache.resolve("61,25@sv", { null }, network("Ny stad"))).isNull()
        assertThat(cache.resolve("61,25@fi", { null }, network("Uusi kaupunki"))).isEqualTo(PlaceName("Vantaa", true))
        assertThat(networkCalls).isEqualTo(1)
        assertThat(cache.resolve("61,25@fi", { null }, null)).isNull()
        assertThat(cache.resolve("60,24@fi", { null }, network(null))).isNull()
    }

    @Test fun aNameResolvedInAnotherLanguageIsNotReused() {
        cache.resolve(placeAreaKey(60.21, 24.87, "en-GB"), local = { "Helsinki" }, network = null)

        assertThat(cache.resolve(placeAreaKey(60.21, 24.87, "fi-FI"), local = { null }, network = null)).isNull()
        assertThat(cache.resolve(placeAreaKey(60.21, 24.87, "en-GB"), local = { null }, network = null)?.name)
            .isEqualTo("Helsinki")
    }

    @Test fun theAreaKeyRoundsTheSameWayInEveryLanguage() {
        assertThat(placeAreaKey(60.214, 24.869, "fi-FI")).isEqualTo("60.21,24.87@fi-FI")
        assertThat(placeAreaKey(60.214, 24.869, "tr-TR")).isEqualTo("60.21,24.87@tr-TR")
    }

    @Test fun anOldNameExpires() {
        cache.resolve("60.21,24.87", local = { "Vantaa" }, network = null)
        now = 1_000

        assertThat(cache.resolve("60.21,24.87", local = { null }, network = null)).isNull()
    }

    /**
     * Review round 3. The weather is renewed every quarter of an hour now, and the privacy statement promises
     * that the place name is asked of the network "at most once per 30 minutes". The network is asked by its
     * own half hour, whatever the weather's step: in between the last name stands.
     */
    @Test fun theNetworkIsAskedAtMostOnceInHalfAnHour() {
        var nowMs = 0L
        var asked = 0
        val cache = PlaceNameCache(maxAgeMs = 24 * 60 * 60_000L, now = { nowMs })
        val network = { asked++; "Oulu" }

        assertThat(cache.resolve("65.01,25.47@fi", local = { null }, network = network)).isEqualTo(PlaceName("Oulu", fromNetwork = true))
        nowMs = 15 * 60_000L
        assertThat(cache.resolve("65.01,25.47@fi", local = { null }, network = network)).isEqualTo(PlaceName("Oulu", fromNetwork = true))
        assertThat(asked).isEqualTo(1)

        nowMs = 30 * 60_000L
        cache.resolve("65.01,25.47@fi", local = { null }, network = network)
        assertThat(asked).isEqualTo(2)
    }

    /** A question the network did not answer counts as asked: it is not put again a quarter of an hour later. */
    @Test fun anUnansweredQuestionIsNotPutAgainWithinTheHalfHour() {
        var nowMs = 0L
        var asked = 0
        val cache = PlaceNameCache(maxAgeMs = 24 * 60 * 60_000L, now = { nowMs })
        val network = { asked++; null }

        cache.resolve("65.01,25.47@fi", local = { null }, network = network)
        nowMs = 29 * 60_000L
        cache.resolve("65.01,25.47@fi", local = { null }, network = network)

        assertThat(asked).isEqualTo(1)
    }
}
