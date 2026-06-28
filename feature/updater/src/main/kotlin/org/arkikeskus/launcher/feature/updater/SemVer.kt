package org.arkikeskus.launcher.feature.updater

/** Compares dot-separated numeric versions, tolerating a leading 'v' and differing component counts. */
object SemVer {
    fun isNewer(candidate: String, current: String): Boolean {
        val c = parse(candidate) ?: return false
        val cur = parse(current) ?: return false
        val n = maxOf(c.size, cur.size)
        for (i in 0 until n) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(raw: String): List<Int>? {
        val s = raw.trim().removePrefix("v").removePrefix("V")
        if (s.isEmpty()) return null
        val parts = s.split(".")
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }
}
