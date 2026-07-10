package org.arkikeskus.launcher.data.search

import org.arkikeskus.launcher.model.SearchResult
import javax.inject.Inject

/**
 * Local calculator + simple unit conversion. Pure (no Android deps). Returns at most one
 * [SearchResult.Calculation]; returns empty on anything that is not a valid expression.
 */
class CalculatorSearchProvider @Inject constructor() : SearchProvider {

    override suspend fun isEnabled(): Boolean = true

    override suspend fun query(query: String): List<SearchResult> {
        // Accept the Finnish decimal comma — the expression is a single value, so a plain
        // comma→dot swap is safe (there are no argument lists to confuse).
        val q = query.trim().replace(',', '.')
        // A lone number like "5" or "3.14" is not a useful calculation — skip it to avoid
        // showing redundant "5 = 5" results.  Unit conversions contain letters, so they pass.
        if (Regex("^-?\\d*\\.?\\d+$").matches(q)) return emptyList()
        val result = evalUnit(q) ?: evalArithmetic(q) ?: return emptyList()
        if (!result.isFinite()) return emptyList()
        // Show the ORIGINAL query (with its comma) as the label so the user sees what they typed.
        return listOf(SearchResult.Calculation(query.trim(), format(result)))
    }

    // --- unit conversion: "<number> <unit> to <unit>" -------------------------------------------
    private fun evalUnit(q: String): Double? {
        val m = UNIT_PATTERN.matchEntire(q.lowercase()) ?: return null
        val amount = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = m.groupValues[2]
        val to = m.groupValues[3]
        return convert(amount, from, to)
    }

    private fun convert(amount: Double, from: String, to: String): Double? {
        // Length (base: metre), mass (base: kg), temperature handled separately.
        return when {
            from in LENGTH && to in LENGTH -> amount * LENGTH.getValue(from) / LENGTH.getValue(to)
            from in MASS && to in MASS -> amount * MASS.getValue(from) / MASS.getValue(to)
            from == "c" && to == "f" -> amount * 9 / 5 + 32
            from == "f" && to == "c" -> (amount - 32) * 5 / 9
            else -> null
        }
    }

    // --- arithmetic: recursive-descent over + - * / and parentheses ----------------------------
    private fun evalArithmetic(raw: String): Double? {
        val s = raw.replace('×', '*').replace('÷', '/').replace('x', '*').replace('X', '*')
        if (s.isBlank() || s.none { it.isDigit() }) return null
        if (s.any { it !in "0123456789.+-*/() " }) return null
        return runCatching { Parser(s).parse() }.getOrNull()
    }

    private fun format(value: Double): String {
        // BigDecimal, not roundToLong: a large finite product (e.g. ~1e24) saturates Long and would
        // print a completely wrong integer. Round to 2 decimals and drop trailing zeros.
        if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
        return java.math.BigDecimal(value)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    /** Minimal recursive-descent parser; throws on malformed input (caught by the caller). */
    private class Parser(text: String) {
        private val s = text.replace(" ", "")
        private var pos = 0
        fun parse(): Double {
            val v = expr()
            if (pos != s.length) error("trailing input")
            return v
        }
        private fun expr(): Double {
            var v = term()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]; val r = term(); v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun term(): Double {
            var v = factor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos++]; val r = factor(); v = if (op == '*') v * r else v / r
            }
            return v
        }
        private fun factor(): Double {
            if (pos < s.length && s[pos] == '(') {
                pos++; val v = expr()
                require(pos < s.length && s[pos] == ')') { "missing )" }; pos++
                return v
            }
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]; return if (op == '-') -factor() else factor()
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            require(pos > start) { "expected number" }
            return s.substring(start, pos).toDouble()
        }
    }

    private companion object {
        val UNIT_PATTERN = Regex("""(-?[\d.]+)\s*([a-z]+)\s+to\s+([a-z]+)""")
        val LENGTH = mapOf("mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "in" to 0.0254, "ft" to 0.3048, "mi" to 1609.344)
        val MASS = mapOf("g" to 0.001, "kg" to 1.0, "lb" to 0.45359237, "oz" to 0.0283495231)
    }
}
