package org.arkikeskus.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/** A per-app launch statistic: a time-decayed [score] and the [lastUsed] epoch-millis timestamp. */
data class UsageStat(val score: Float, val lastUsed: Long)

/**
 * Tracks how often each app is launched, as a time-decayed "frecency" score. Each launch decays the
 * existing score to now and adds 1; ranking decays every score to now for a fair comparison, so a
 * one-off launch fades below apps used regularly. Stored in the same [DataStore] as the other
 * preferences (serialized one app per line, tab-separated: `key\tscore\tlastUsed`).
 */
@Singleton
class AppUsageRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val usage: Flow<Map<String, UsageStat>> = dataStore.data.map { parse(it[Keys.APP_USAGE]) }

    /** Records a launch of [key] now: decays its stored score to now, then adds 1. Best effort: a
     *  write the storage refuses (a full disk) is dropped — the caller fires this on the application
     *  scope, where an escaping exception would kill the home process after every app launch. */
    suspend fun recordLaunch(key: String) {
        try {
            dataStore.edit { p ->
                val stats = parse(p[Keys.APP_USAGE]).toMutableMap()
                stats[key] = applyLaunch(stats[key], System.currentTimeMillis())
                p[Keys.APP_USAGE] = serialize(stats)
            }
        } catch (e: IOException) {
            // Dropped: a usage score is not worth a crash, and the next launch records again.
        }
    }

    /** The moment up to which Android's own record of opened apps has been counted in ([SystemUsageSync]);
     *  0 = never. */
    val systemSyncedTo: Flow<Long> = dataStore.data.map { it[Keys.SYSTEM_SYNCED_TO] ?: 0L }

    /**
     * Counts [openings] (an app's key and when it came to the front, oldest first) as launches, and notes that
     * Android's record has been read up to [syncedTo]: one write, so a count is never stored without its time
     * and read again. An opening within [SAME_LAUNCH_MS] of the app's stored last launch is the launch the
     * launcher itself recorded a moment earlier (Android reports it too) and is not counted twice. An opening
     * older than the stored last launch still counts, at that last launch's time: a score's time never moves
     * back. Best effort, as [recordLaunch]: a refused write is dropped and read again at the next sync.
     */
    suspend fun recordOpenings(openings: List<Pair<String, Long>>, syncedTo: Long) {
        try {
            dataStore.edit { p ->
                val stats = parse(p[Keys.APP_USAGE]).toMutableMap()
                for ((key, at) in openings) {
                    val prev = stats[key]
                    if (prev != null && kotlin.math.abs(at - prev.lastUsed) <= SAME_LAUNCH_MS) continue
                    stats[key] = applyLaunch(prev, maxOf(at, prev?.lastUsed ?: at))
                }
                if (openings.isNotEmpty()) p[Keys.APP_USAGE] = serialize(stats)
                p[Keys.SYSTEM_SYNCED_TO] = syncedTo
            }
        } catch (e: IOException) {
            // Dropped: the stored time did not move, so the next sync reads the same span again.
        }
    }

    private fun parse(raw: String?): Map<String, UsageStat> =
        raw?.split("\n")?.filter { it.isNotEmpty() }?.mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size != 3) return@mapNotNull null
            val score = parts[1].toFloatOrNull() ?: return@mapNotNull null
            val lastUsed = parts[2].toLongOrNull() ?: return@mapNotNull null
            parts[0] to UsageStat(score, lastUsed)
        }?.toMap() ?: emptyMap()

    private fun serialize(stats: Map<String, UsageStat>): String =
        stats.entries.joinToString("\n") { (k, s) -> "$k\t${s.score}\t${s.lastUsed}" }

    private object Keys {
        val APP_USAGE = stringPreferencesKey(USAGE_KEY)
        val SYSTEM_SYNCED_TO = longPreferencesKey(SYSTEM_SYNC_KEY)
    }

    companion object {
        /** DataStore key name for the serialized usage map. Exposed so [SettingsRepository] can
         *  exclude this volatile, device-local data from a backup (it otherwise changed the Drive
         *  dedup hash on every launch → a redundant upload each run). */
        const val USAGE_KEY = "app_usage"

        /** DataStore key name of the time Android's record was last read up to; this phone's own, as [USAGE_KEY]. */
        const val SYSTEM_SYNC_KEY = "app_usage_system_synced_to"

        /** Two reports of one launch (the launcher's own and Android's) are at most this far apart. */
        const val SAME_LAUNCH_MS = 10_000L

        /** Half-life of the launch score; recent launches dominate within ~1–2 weeks. */
        const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

        /** Decayed score of [stat] at [atMillis]; a backwards clock clamps elapsed to 0 (no inflation). */
        fun currentScore(stat: UsageStat, atMillis: Long): Float {
            val elapsed = (atMillis - stat.lastUsed).coerceAtLeast(0L)
            return (stat.score * 0.5.pow(elapsed.toDouble() / HALF_LIFE_MS)).toFloat()
        }

        /** The stat after a launch at [atMillis]: decayed previous score + 1, timestamped at [atMillis]. */
        fun applyLaunch(prev: UsageStat?, atMillis: Long): UsageStat {
            val decayed = prev?.let { currentScore(it, atMillis) } ?: 0f
            return UsageStat(decayed + 1f, atMillis)
        }
    }
}
