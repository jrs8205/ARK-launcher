package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.search.CalculatorSearchProvider
import org.arkikeskus.launcher.model.SearchResult
import org.junit.Test

class CalculatorSearchProviderTest {
    private val provider = CalculatorSearchProvider()

    @Test fun `evaluates precedence and parentheses`() = runTest {
        val r = provider.query("2+3*4") .filterIsInstance<SearchResult.Calculation>().single()
        assertThat(r.result).isEqualTo("14")
        val r2 = provider.query("(2+3)*4").filterIsInstance<SearchResult.Calculation>().single()
        assertThat(r2.result).isEqualTo("20")
    }

    @Test fun `supports decimals and division`() = runTest {
        assertThat(provider.query("7/2").filterIsInstance<SearchResult.Calculation>().single().result)
            .isEqualTo("3.5")
    }

    @Test fun `accepts x and unicode operators`() = runTest {
        assertThat(provider.query("12 x 7").filterIsInstance<SearchResult.Calculation>().single().result)
            .isEqualTo("84")
    }

    @Test fun `converts simple units`() = runTest {
        val r = provider.query("100 cm to in").filterIsInstance<SearchResult.Calculation>().single()
        assertThat(r.result).isEqualTo("39.37")
    }

    @Test fun `returns empty for non-expressions`() = runTest {
        assertThat(provider.query("camera")).isEmpty()
        assertThat(provider.query("")).isEmpty()
        assertThat(provider.query("2+")).isEmpty()
    }
}
