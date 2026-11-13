package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where a profile's apps are listed: the shared list, the private space's own, or nowhere. */
class ProfileListingTest {
    private val kinds = mapOf(
        0L to ProfileKind.Own,
        10L to ProfileKind.Managed,
        11L to ProfileKind.Private,
        12L to ProfileKind.Clone,
        13L to ProfileKind.Unknown,
    )

    @Test fun theOwnUserIsAlwaysInTheSharedList() {
        for (sdk in listOf(30, 34, 35, 36)) {
            assertThat(listingOf(isOwnUser = true, serial = 0, kinds = kinds, sdkInt = sdk)).isEqualTo(Listing.General)
            // Even when nothing is known about it.
            assertThat(listingOf(isOwnUser = true, serial = null, kinds = emptyMap(), sdkInt = sdk)).isEqualTo(Listing.General)
        }
    }

    @Test fun belowAndroid15EveryProfileIsInTheSharedListAsBefore() {
        for (sdk in listOf(30, 34)) {
            for (serial in listOf(10L, 11L, 12L, 13L, 99L)) {
                assertThat(listingOf(false, serial, kinds, sdk)).isEqualTo(Listing.General)
            }
            assertThat(listingOf(false, null, kinds, sdk)).isEqualTo(Listing.General)
        }
    }

    @Test fun fromAndroid15OnlyATypedProfileIsListed() {
        for (sdk in listOf(35, 36)) {
            assertThat(listingOf(false, 10, kinds, sdk)).isEqualTo(Listing.General) // a work profile
            assertThat(listingOf(false, 12, kinds, sdk)).isEqualTo(Listing.General) // a clone
            assertThat(listingOf(false, 11, kinds, sdk)).isEqualTo(Listing.Private)
        }
    }

    @Test fun fromAndroid15AProfileOfUnknownKindIsListedNowhere() {
        for (sdk in listOf(35, 36)) {
            assertThat(listingOf(false, 13, kinds, sdk)).isEqualTo(Listing.Nowhere) // untyped
            assertThat(listingOf(false, 99, kinds, sdk)).isEqualTo(Listing.Nowhere) // not in the snapshot
            assertThat(listingOf(false, null, kinds, sdk)).isEqualTo(Listing.Nowhere) // no serial to be had
        }
    }
}
