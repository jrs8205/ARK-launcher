package org.arkikeskus.launcher.data.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaceNameFallbackTest {

    @Test
    fun `city wins over locality`() {
        val json = """{"city":"Vantaa","locality":"Martinlaakso","principalSubdivision":"Uusimaa"}"""
        assertThat(PlaceNameFallback.parseCity(json)).isEqualTo("Vantaa")
    }

    @Test
    fun `locality used when city blank`() {
        val json = """{"city":"","locality":"Martinlaakso"}"""
        assertThat(PlaceNameFallback.parseCity(json)).isEqualTo("Martinlaakso")
    }

    @Test
    fun `principalSubdivision is the last resort`() {
        val json = """{"city":"","locality":"","principalSubdivision":"Uusimaa"}"""
        assertThat(PlaceNameFallback.parseCity(json)).isEqualTo("Uusimaa")
    }

    @Test
    fun `all blank gives null`() {
        assertThat(PlaceNameFallback.parseCity("""{"city":""}""")).isNull()
    }

    @Test
    fun `malformed json gives null`() {
        assertThat(PlaceNameFallback.parseCity("not json")).isNull()
    }
}
