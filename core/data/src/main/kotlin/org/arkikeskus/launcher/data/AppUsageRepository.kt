package org.arkikeskus.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    /** Records a launch of [key] at [at] (now): decays its stored score to then, and adds 1. While apps opened
     *  elsewhere are counted too ([COUNTING_KEY]), the launch is also remembered until Android's record of that
     *  moment has been read ([ownLaunches]): Android reports this launch as well, and it is one launch. Best
     *  effort: a write the storage refuses (a full disk) is dropped — the caller fires this on the application
     *  scope, where an escaping exception would kill the home process after every app launch. */
    suspend fun recordLaunch(key: String, at: Long = System.currentTimeMillis()) {
        try {
            dataStore.edit { p ->
                val stats = parse(p[Keys.APP_USAGE]).toMutableMap()
                stats[key] = applyLaunch(stats[key], at)
                p[Keys.APP_USAGE] = serialize(stats)
                if (p[Keys.COUNTING] == true) {
                    val launches = parseLaunches(p[Keys.OWN_LAUNCHES]) + (key to at)
                    p[Keys.OWN_LAUNCHES] = serializeLaunches(launches.takeLast(MAX_OWN_LAUNCHES))
                }
            }
        } catch (e: IOException) {
            // Dropped: a usage score is not worth a crash, and the next launch records again.
        }
    }

    /** The moment up to which Android's own record of opened apps has been counted in ([SystemUsageSync]):
     *  the moment the count was switched on, until the first read. 0 = not counting, or no start noted. */
    val systemSyncedTo: Flow<Long> = dataStore.data.map { it[Keys.SYSTEM_SYNCED_TO] ?: 0L }

    /** The launches made in the launcher that Android's record may still report (an app's key and when,
     *  oldest first): every one since [systemSyncedTo], [MAX_OWN_LAUNCHES] at most. Empty while apps opened
     *  elsewhere are not counted. */
    internal val ownLaunches: Flow<List<Pair<String, Long>>> = dataStore.data.map { parseLaunches(it[Keys.OWN_LAUNCHES]) }

    /**
     * Counts [openings] (an app's key and when it came to the front, oldest first) as launches, and notes that
     * Android's record has been read up to [syncedTo]: one write, so a count is never stored without its time
     * and read again. An opening within [SAME_LAUNCH_MS] of a launch the launcher made itself of the same app
     * ([ownLaunches]) is Android's report of that launch, in the score already: it is left out, and that launch
     * answers for no other report. An opening older than the app's stored last launch counts at that last
     * launch's time: a score's time never moves back. Best effort, as [recordLaunch]: a refused write is
     * dropped and read again at the next sync.
     */
    suspend fun recordOpenings(openings: List<Pair<String, Long>>, syncedTo: Long) {
        try {
            dataStore.edit { p ->
                val stats = parse(p[Keys.APP_USAGE]).toMutableMap()
                val own = parseLaunches(p[Keys.OWN_LAUNCHES]).toMutableList()
                for ((key, at) in openings) {
                    val app = appOf(key)
                    val reported = own.indexOfFirst { (launched, then) ->
                        appOf(launched) == app && kotlin.math.abs(at - then) <= SAME_LAUNCH_MS
                    }
                    if (reported >= 0) {
                        own.removeAt(reported)
                        continue
                    }
                    val prev = stats[key]
                    stats[key] = applyLaunch(prev, maxOf(at, prev?.lastUsed ?: at))
                }
                if (openings.isNotEmpty()) p[Keys.APP_USAGE] = serialize(stats)
                // A launch of the last moments may be reported only in the next read. An older one that no
                // report answered was never going to be reported (a work profile's app, a launch that showed
                // nothing): it goes.
                val waiting = own.filter { (_, then) -> then > syncedTo - SAME_LAUNCH_MS }
                if (waiting.isEmpty()) p.remove(Keys.OWN_LAUNCHES) else p[Keys.OWN_LAUNCHES] = serializeLaunches(waiting)
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

    /** One launch per line, `key\ttime`. */
    private fun parseLaunches(raw: String?): List<Pair<String, Long>> =
        raw?.split("\n")?.mapNotNull { line ->
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) return@mapNotNull null
            line.substring(0, tab) to (line.substring(tab + 1).toLongOrNull() ?: return@mapNotNull null)
        } ?: emptyList()

    private fun serializeLaunches(launches: List<Pair<String, Long>>): String =
        launches.joinToString("\n") { (key, at) -> "$key\t$at" }

    /** The app an app key (`package/class/userSerial`) is an entry of: its package and its user. Android names
     *  the screen that came to the front, which need not be the entry that was started, and a work profile's
     *  app has the personal app's package. */
    private fun appOf(key: String): Pair<String, String> = key.substringBefore('/') to key.substringAfterLast('/')

    private object Keys {
        val APP_USAGE = stringPreferencesKey(USAGE_KEY)
        val SYSTEM_SYNCED_TO = longPreferencesKey(SYSTEM_SYNC_KEY)
        val OWN_LAUNCHES = stringPreferencesKey(OWN_LAUNCHES_KEY)
        val COUNTING = booleanPreferencesKey(COUNTING_KEY)
    }

    companion object {
        /** DataStore key name for the serialized usage map. Exposed so [SettingsRepository] can
         *  exclude this volatile, device-local data from a backup (it otherwise changed the Drive
         *  dedup hash on every launch → a redundant upload each run). */
        const val USAGE_KEY = "app_usage"

        /** DataStore key name of the time Android's record was last read up to; this phone's own, as [USAGE_KEY]. */
        const val SYSTEM_SYNC_KEY = "app_usage_system_synced_to"

        /** DataStore key name of the launcher's own launches that Android's record may still report
         *  ([ownLaunches]); this phone's own, as [USAGE_KEY]. */
        const val OWN_LAUNCHES_KEY = "app_usage_own_launches"

        /** DataStore key name of the setting "Count apps opened elsewhere" ([SettingsRepository] owns it). */
        const val COUNTING_KEY = "count_system_usage"

        /** How many of the launcher's own launches are remembered for the next read of Android's record. They
         *  are few on a phone whose drawer is opened now and then (a read forgets what it has passed); this is
         *  for one where it never is, or the access is never given, so that the settings file does not grow.
         *  Past it the oldest go, and such a launch counts twice if Android's record of it is ever read. */
        const val MAX_OWN_LAUNCHES = 200

        /**
         * The setting "Count apps opened elsewhere" went from [was] to [on] at [now], in [prefs] (the caller's
         * own write of the setting). Turned on, the count starts at that moment: what was opened before is not
         * read, since the launcher's own launches of that time are in the scores and were not remembered one
         * by one. Turned off, the bookkeeping goes. Left on, nothing changes.
         */
        fun countingSwitched(prefs: MutablePreferences, was: Boolean, on: Boolean, now: Long) {
            if (was && on) return
            prefs.remove(Keys.OWN_LAUNCHES)
            if (on) prefs[Keys.SYSTEM_SYNCED_TO] = now else prefs.remove(Keys.SYSTEM_SYNCED_TO)
        }

        /** Two reports of one launch (the launcher's own, made as it starts the app, and Android's, made when
         *  the app's screen is up) are at most this far apart. */
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
