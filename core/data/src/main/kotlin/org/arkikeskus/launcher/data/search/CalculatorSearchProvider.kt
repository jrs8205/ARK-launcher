package org.arkikeskus.launcher.data.search

import org.arkikeskus.launcher.model.SearchResult
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * Local calculator + simple unit conversion. Pure (no Android deps). Returns at most one
 * [SearchResult.Calculation]; returns empty on anything that is not a valid expression.
 */
class CalculatorSearchProvider @Inject constructor() : SearchProvider {

    override suspend fun isEnabled(): Boolean = true

    override suspend fun query(query: String): List<SearchResult> {
        val q = query.trim()
        val result = evalUnit(q) ?: evalArithmetic(q) ?: return emptyList()
        return listOf(SearchResult.Calculation(q, format(result)))
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
        val length = mapOf("mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "in" to 0.0254, "ft" to 0.3048, "mi" to 1609.344)
        val mass = mapOf("g" to 0.001, "kg" to 1.0, "lb" to 0.45359237, "oz" to 0.0283495231)
        return when {
            from in length && to in length -> amount * length.getValue(from) / length.getValue(to)
            from in mass && to in mass -> amount * mass.getValue(from) / mass.getValue(to)
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
        val rounded = (value * 100).roundToLong() / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
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
        val UNIT_PATTERN = Regex("""([\d.]+)\s*([a-z]+)\s+to\s+([a-z]+)""")
    }
}
