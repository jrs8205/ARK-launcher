package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.search.CalculatorSearchProvider
import org.arkikeskus.launcher.model.SearchResult
import org.junit.Test

class CalculatorSearchProviderTest {
    private val provider = CalculatorSearchProvider()

    @Test fun `evaluates precedence and parentheses`() = runTest {
        val r = provider.query("2+3*4").filterIsInstance<SearchResult.Calculation>().single()
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

    @Test fun `returns empty and never throws on division by zero`() = runTest {
        assertThat(provider.query("7/0")).isEmpty()
        assertThat(provider.query("0/0")).isEmpty()
    }

    // Bare-number guard: a lone integer or decimal is not a calculation ("5 = 5" is noise).
    @Test fun `returns empty for a bare integer`() = runTest {
        assertThat(provider.query("5")).isEmpty()
        assertThat(provider.query("2017")).isEmpty()
    }

    @Test fun `returns empty for a bare decimal`() = runTest {
        assertThat(provider.query("3.14")).isEmpty()
    }

    @Test fun `bare number with operator still evaluates`() = runTest {
        // Regression: ensure the guard does not suppress real expressions.
        assertThat(provider.query("5+0").filterIsInstance<SearchResult.Calculation>()).isNotEmpty()
    }

    @Test fun `converts a negative temperature`() = runTest {
        assertThat(provider.query("-40 c to f").filterIsInstance<SearchResult.Calculation>().single().result)
            .isEqualTo("-40")
    }

    @Test fun `accepts a Finnish decimal comma`() = runTest {
        assertThat(provider.query("3,5+1").filterIsInstance<SearchResult.Calculation>().single().result)
            .isEqualTo("4.5")
    }

    @Test fun `large finite products do not saturate to a wrong integer`() = runTest {
        val r = provider.query("999999999999*999999999999").filterIsInstance<SearchResult.Calculation>().single()
        // A Long.MAX_VALUE saturation artefact would contain 922337 (9.22e18); the true value ~1e24 must not.
        assertThat(r.result).doesNotContain("922337")
    }
}
