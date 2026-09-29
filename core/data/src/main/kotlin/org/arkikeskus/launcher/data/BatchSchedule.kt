package org.arkikeskus.launcher.data

import java.time.Instant
import java.time.ZoneId

/** The people widget's batch delivery times: parsing, formatting and "when is the next one". */
object BatchSchedule {

    /** "HH:mm" entries separated by commas, spaces or semicolons → sorted distinct minutes of day.
     *  Entries that don't parse are dropped; an empty result means "no valid time". */
    fun parse(raw: String): List<Int> =
        raw.split(',', ';', ' ', '\n').mapNotNull { part ->
            val t = part.trim()
            if (t.isEmpty()) return@mapNotNull null
            val m = TIME.matchEntire(t) ?: return@mapNotNull null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) null else h * 60 + min
        }.distinct().sorted()

    fun format(minutes: List<Int>): String =
        minutes.sorted().joinToString(",") { "%02d:%02d".format(it / 60, it % 60) }

    /** Epoch ms of the first delivery strictly after [nowMs] (today or tomorrow); null without times. */
    fun nextDelivery(nowMs: Long, times: List<Int>, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (times.isEmpty()) return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        for (day in 0L..1L) {
            val start = today.plusDays(day).atStartOfDay(zone)
            for (t in times.sorted()) {
                val at = start.plusMinutes(t.toLong()).toInstant().toEpochMilli()
                if (at > nowMs) return at
            }
        }
        return null
    }

    private val TIME = Regex("(\\d{1,2})[.:](\\d{2})")
}
