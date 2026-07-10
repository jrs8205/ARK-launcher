package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaceNamesTest {

    @Test
    fun `locality wins over subLocality`() {
        assertThat(PlaceNames.preferred(listOf("Vantaa", "Martinlaakso"))).isEqualTo("Vantaa")
    }

    @Test
    fun `subLocality used when locality missing`() {
        assertThat(PlaceNames.preferred(listOf(null, "Martinlaakso", "Vantaa"))).isEqualTo("Martinlaakso")
    }

    @Test
    fun `later fallbacks used in order`() {
        assertThat(PlaceNames.preferred(listOf(null, null, "Uusimaa"))).isEqualTo("Uusimaa")
    }

    @Test
    fun `blank and whitespace values are ignored`() {
        assertThat(PlaceNames.preferred(listOf("", "   ", "Espoo"))).isEqualTo("Espoo")
    }

    @Test
    fun `result is trimmed`() {
        assertThat(PlaceNames.preferred(listOf("  Espoo  "))).isEqualTo("Espoo")
    }

    @Test
    fun `all missing gives null`() {
        assertThat(PlaceNames.preferred(listOf(null, "", " "))).isNull()
    }
}
