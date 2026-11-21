package org.arkikeskus.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/** A per-app launch statistic: a time-decayed [score] and the [lastUsed] epoch-millis timestamp. */
data class UsageStat(val score: Float, val lastUsed: Long)

/**
 * The count of apps opened elsewhere as the store holds it, read in one piece: which switching-on it is of
 * ([period]: the number of times the count has been switched on or off), the moment Android's record has been
 * read up to ([syncedTo]; the moment it was switched on, until the first read; 0 = no start noted), and the
 * package that was in front at that moment ([inFront]; null when none is known).
 */
data class SystemCount(val period: Long, val syncedTo: Long, val inFront: String?)

/**
 * An app that came to the front as Android reported it, for the count: the launcher entry it counts for
 * ([key]), when ([at]), and whether that entry IS the screen that came up ([exact]) and not the package's first
 * entry standing for a screen inside the app.
 */
data class CountedOpening(val key: String, val at: Long, val exact: Boolean = true)

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

    /** Records a launch of [key] at [at] (the moment it was asked for): decays its stored score to then, and
     *  adds 1. While apps opened elsewhere are counted too ([COUNTING_KEY]), a launch Android reports as well
     *  ([androidReports]) is also remembered until Android's record of that moment has been read
     *  ([ownLaunches]): it is one launch. Android tells the launcher only of the user it runs as, so a work
     *  profile's app is never remembered so: no report could ever answer for it, and it would only take the
     *  room of a launch that one does. Best effort: a write the storage refuses (a full disk) is dropped — the
     *  caller fires this on the application scope, where an escaping exception would kill the home process
     *  after every app launch. */
    suspend fun recordLaunch(
        key: String,
        at: Long = System.currentTimeMillis(),
        androidReports: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ) = recordLaunch(AskedLaunch(key, at, androidReports, PERIOD_NOT_KNOWN), now)

    /**
     * A launch the launcher has asked Android for, from the moment it is asked ([launchAsked]) until its own
     * note is in the store ([recordLaunch]). The note is written by a coroutine of its own and can come late:
     * a read of Android's record that gets to the store first ([recordOpenings]) finds the launch here, so
     * Android's report of it is known for the launcher's own in whichever order the two are written.
     *
     * [period] is the count the launch was asked in ([countPeriod] at that moment). Only in THAT count does it
     * wait for Android's report: its note can be written after the switch was turned, and the record of the
     * new count starts after the launch, so its report is never read there. Left waiting, it was taken for
     * the app's first real opening from elsewhere in the new count, and that opening was not counted.
     */
    class AskedLaunch internal constructor(val key: String, val at: Long, val androidReports: Boolean, val period: Long) {
        /** Android's report of this launch has been read and left out of the count: it waits for none. Set
         *  and read under the lock the notes are written under ([notes]). */
        @Volatile internal var answered = false

        /** Whether the launch was asked in the count [period] (one whose count is not known counts for any). */
        internal fun isOf(period: Long): Boolean = this.period == PERIOD_NOT_KNOWN || this.period == period
    }

    /** The launches asked for whose own note is not in the store yet. Read and changed under its own lock. */
    private val asked = ArrayList<AskedLaunch>()

    /**
     * Held over each write that decides whether a launch waits for Android's report: a launch's own note
     * ([recordLaunch]) and a read of Android's record with the word it leaves for the launches it met
     * ([recordOpenings]). The store's own lock ends with the write; a read whose write was refused took its
     * word back after that, and a note written in between believed it.
     */
    private val notes = Mutex()

    /**
     * Which count a launch asked for now is of: the [SystemCount.period] while apps opened elsewhere are
     * counted, [NO_COUNT] while they are not. For the caller of [launchAsked], which cannot wait for a read
     * and so follows this.
     */
    val countPeriod: Flow<Long> = dataStore.data
        .map { p -> if (p[Keys.COUNTING] == true) p[Keys.COUNTING_PERIOD] ?: 0L else NO_COUNT }
        .distinctUntilChanged()

    /**
     * The launcher is about to ask Android to start [key], at [at]. Called BEFORE the start, on the caller's
     * thread: from here on a read of Android's record knows of the launch. The caller then either records it
     * ([recordLaunch]) or, when Android refused the start, takes it back ([launchNotMade]). A launch Android
     * does not report ([androidReports] false: a work profile's app) is not kept here: no report is to be
     * told from it. [period] is the count it is asked in ([AskedLaunch.period]).
     */
    fun launchAsked(
        key: String,
        at: Long = System.currentTimeMillis(),
        androidReports: Boolean = true,
        period: Long = PERIOD_NOT_KNOWN,
    ): AskedLaunch {
        val launch = AskedLaunch(key, at, androidReports, period)
        if (androidReports) synchronized(asked) { asked += launch }
        return launch
    }

    /** [launch] was not made after all: nothing is recorded of it. */
    fun launchNotMade(launch: AskedLaunch) {
        synchronized(asked) { asked.remove(launch) }
    }

    /** [recordLaunch] of a launch that was announced with [launchAsked]. [now] is the moment of this note: the
     *  clock as it is when the note is written, which a stored time is judged by ([applyNoted]). */
    suspend fun recordLaunch(launch: AskedLaunch, now: Long = System.currentTimeMillis()): Unit = notes.withLock {
        try {
            dataStore.edit { p ->
                val stats = parse(p[Keys.APP_USAGE]).toMutableMap()
                // By its own time, as an opening read late is: a later opening of the app from elsewhere can
                // have been counted before this note is written, and the app's time must not go back to the
                // launch's (the later opening would then fade from too early a moment). Unless the stored
                // time is LATER THAN NOW: that is a clock that was set back, not a note that came late.
                stats[launch.key] = applyNoted(stats[launch.key], launch.at, now)
                p[Keys.APP_USAGE] = serialize(stats)
                // Not when a read of Android's record has met this launch's report already (the read got to
                // the store before this note did): the launch then waits for no report, and kept waiting it
                // would be taken for the next opening of the app from somewhere else. And only in the count
                // it was asked in ([AskedLaunch.period]).
                val waits = launch.androidReports && !launch.answered &&
                    p[Keys.COUNTING] == true && launch.isOf(p[Keys.COUNTING_PERIOD] ?: 0L)
                if (waits) {
                    val launches = parseLaunches(p[Keys.OWN_LAUNCHES]) + (launch.key to launch.at)
                    p[Keys.OWN_LAUNCHES] = serializeLaunches(launches.takeLast(MAX_OWN_LAUNCHES))
                }
            }
        } catch (e: IOException) {
            // Dropped: a usage score is not worth a crash, and the next launch records again.
        } finally {
            synchronized(asked) { asked.remove(launch) }
        }
    }

    /** The moment up to which Android's own record of opened apps has been counted in ([SystemUsageSync]):
     *  the moment the count was switched on, until the first read. 0 = not counting, or no start noted. */
    val systemSyncedTo: Flow<Long> = dataStore.data.map { it[Keys.SYSTEM_SYNCED_TO] ?: 0L }

    /** The count of apps opened elsewhere as it stands ([SystemCount]); null while it is switched off. */
    val systemCount: Flow<SystemCount?> = dataStore.data.map { p -> if (p[Keys.COUNTING] == true) countIn(p) else null }

    private fun countIn(p: Preferences) =
        SystemCount(p[Keys.COUNTING_PERIOD] ?: 0L, p[Keys.SYSTEM_SYNCED_TO] ?: 0L, p[Keys.SYSTEM_FRONT])

    /** The launches made in the launcher that Android's record may still report (an app's key and when,
     *  oldest first): every one since [systemSyncedTo], [MAX_OWN_LAUNCHES] at most. Empty while apps opened
     *  elsewhere are not counted. */
    internal val ownLaunches: Flow<List<Pair<String, Long>>> = dataStore.data.map { parseLaunches(it[Keys.OWN_LAUNCHES]) }

    /**
     * Counts [openings] (oldest first) as launches, and notes that Android's record has been read up to
     * [syncedTo], with [inFront] the package in front at that moment: ONE write, so a count is never stored
     * without its time and read again.
     *
     * The write holds only for the count the read was made for ([read]: what [systemCount] gave before the
     * reading began). If the switch was turned meanwhile (off, or off and on again: another
     * [SystemCount.period]), or the time read up to is no longer where this read started, nothing is written:
     * the openings are of a count that is no longer on, and their time would replace the new one's start.
     *
     * An opening that is Android's report of a launch the launcher made itself is in the score already (or
     * will be, when the launch's own note is written) and is left out. The launches are the ones in the store
     * ([ownLaunches]) and the ones asked for whose note has not been written yet ([launchAsked]), taken
     * oldest first. A launch's report is an opening of the same app at the launch's moment or within
     * [SAME_LAUNCH_MS] after it, never before it: what came to the front before the launch was asked for is
     * another opening. Of the openings that came BEFORE the app was started again, the one that came up as the
     * very entry the launcher started ([CountedOpening.exact]) is it, else the first of them; with none of
     * those, the first that follows. (An opening after the next start is left to that start: taken for this
     * one's by its screen, it left the next start without a report and an earlier screen counted as an opening
     * from elsewhere, three launches for two.) One launch answers for one report. An opening older than the
     * app's stored last launch adds what it is worth by then ([applyOpening]). Best effort, as [recordLaunch]:
     * a refused write is dropped and read again at the next sync.
     *
     * A launch whose note is still on its way is told that its report was met only once the write is in the
     * store, and under the lock its note is written under ([notes]): no note can decide between the write and
     * the word. (Told inside the write, a write the storage then refused left the word standing for a moment,
     * a note written in that moment did not keep the launch waiting, and the next read, of the same span,
     * counted the launch's report as an opening from elsewhere.) Only the launches asked in this count are
     * looked at ([AskedLaunch.period]).
     */
    suspend fun recordOpenings(read: SystemCount, openings: List<CountedOpening>, syncedTo: Long, inFront: String?): Unit = notes.withLock {
        // The launches asked for whose report this read meets.
        val met = ArrayList<AskedLaunch>()
        try {
            dataStore.edit { p ->
                met.clear()
                if (!isCount(p, read)) return@edit
                val stored = parseLaunches(p[Keys.OWN_LAUNCHES])
                val onTheirWay = synchronized(asked) { asked.filter { !it.answered && it.isOf(read.period) } }
                    .filter { (it.key to it.at) !in stored }
                val launches = (stored.map { (key, at) -> OwnLaunch(key, at, null) } + onTheirWay.map { OwnLaunch(it.key, it.at, it) })
                    .sortedBy { it.at }
                val left = openings.toMutableList()
                val unanswered = ArrayList<Pair<String, Long>>()
                launches.forEachIndexed { index, launch ->
                    val app = appOf(launch.key)
                    val startedAgain = launches.subList(index + 1, launches.size).firstOrNull { appOf(it.key) == app }?.at ?: Long.MAX_VALUE
                    val inTime = left.indices.filter { appOf(left[it].key) == app && left[it].at in launch.at..launch.at + SAME_LAUNCH_MS }
                    val ofThisStart = inTime.filter { left[it].at < startedAgain }
                    val report = ofThisStart.firstOrNull { left[it].exact && left[it].key == launch.key }
                        ?: ofThisStart.firstOrNull()
                        ?: inTime.firstOrNull()
                    when {
                        report != null -> {
                            left.removeAt(report)
                            launch.asked?.let { met += it }
                        }
                        // One whose note is not written yet is kept by that note, not here.
                        launch.asked == null -> unanswered += launch.key to launch.at
                    }
                }
                if (left.isNotEmpty()) {
                    val stats = parse(p[Keys.APP_USAGE]).toMutableMap()
                    for ((key, at) in left) stats[key] = applyNoted(stats[key], at, syncedTo)
                    p[Keys.APP_USAGE] = serialize(stats)
                }
                // A launch of the last moments may be reported only in the next read. An older one that no
                // report answered was never going to be reported (a launch that showed nothing): it goes.
                val waiting = unanswered.filter { (_, then) -> then > syncedTo - SAME_LAUNCH_MS }
                if (waiting.isEmpty()) p.remove(Keys.OWN_LAUNCHES) else p[Keys.OWN_LAUNCHES] = serializeLaunches(waiting)
                p[Keys.SYSTEM_SYNCED_TO] = syncedTo
                if (inFront == null) p.remove(Keys.SYSTEM_FRONT) else p[Keys.SYSTEM_FRONT] = inFront
            }
        } catch (e: IOException) {
            // A refused write is dropped, as a launch's is: nothing was written, so no report was met, and the
            // next sync reads the same span again. (Anything else is the caller's to know, and tells no launch
            // anything either.)
            return@withLock
        }
        met.forEach { it.answered = true }
    }

    /** One of the launcher's own launches for a read to tell reports by: from the store ([asked] null), or
     *  one whose own note is still on its way there. */
    private class OwnLaunch(val key: String, val at: Long, val asked: AskedLaunch?)

    /** Whether the count in [p] is on and is still [read]: the same switching-on, read up to the same moment. */
    private fun isCount(p: Preferences, read: SystemCount): Boolean {
        if (p[Keys.COUNTING] != true) return false
        val stored = countIn(p)
        return stored.period == read.period && stored.syncedTo == read.syncedTo
    }

    /**
     * Starts the reading of Android's record anew at [at], for the count [read]: the stored time read up to is
     * LATER than now, so the phone's clock has been set back since (a read made while it was a week ahead),
     * and every read would wait for that time to come again. Nothing is read back; the launches that waited
     * for a report and the app in front go, as they are of times that are no longer so. It is the same
     * switching-on of the count. Nothing happens when the count is no longer [read].
     */
    suspend fun restartCount(read: SystemCount, at: Long) {
        try {
            dataStore.edit { p ->
                if (!isCount(p, read)) return@edit
                p.remove(Keys.OWN_LAUNCHES)
                p.remove(Keys.SYSTEM_FRONT)
                p[Keys.SYSTEM_SYNCED_TO] = at
            }
        } catch (e: IOException) {
            // Dropped: the next sync finds the same and tries again.
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
        val SYSTEM_FRONT = stringPreferencesKey(SYSTEM_FRONT_KEY)
        val COUNTING_PERIOD = longPreferencesKey(COUNTING_PERIOD_KEY)
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

        /** DataStore key name of the package that was in front at the moment Android's record was last read
         *  up to ([SystemCount.inFront]); this phone's own, as [USAGE_KEY]. */
        const val SYSTEM_FRONT_KEY = "app_usage_system_front"

        /** DataStore key name of the number of times the count has been switched on or off
         *  ([SystemCount.period]). It only ever grows, and stays while the count is off: a read made for one
         *  switching-on is never taken for another's. This phone's own, as [USAGE_KEY]. */
        const val COUNTING_PERIOD_KEY = "app_usage_counting_period"

        /** DataStore key name of the setting "Count apps opened elsewhere" ([SettingsRepository] owns it). */
        const val COUNTING_KEY = "count_system_usage"

        /** [countPeriod] while apps opened elsewhere are not counted: a launch asked then waits for no report. */
        const val NO_COUNT = -1L

        /** The count a launch was asked in is not known (the store had not been read yet): it is taken to be
         *  of whichever count is on when it is looked at, as every launch was before launches named theirs. */
        const val PERIOD_NOT_KNOWN = -2L

        /** How many of the launcher's own launches are remembered for the next read of Android's record. They
         *  are few on a phone whose drawer is opened now and then (a read forgets what it has passed); this is
         *  for one where it never is, or the access is never given, so that the settings file does not grow.
         *  Past it the oldest go, and such a launch counts twice if Android's record of it is ever read. */
        const val MAX_OWN_LAUNCHES = 200

        /**
         * The setting "Count apps opened elsewhere" went from [was] to [on] at [now], in [prefs] (the caller's
         * own write of the setting). Turned on, the count starts at that moment: what was opened before is not
         * read, since the launcher's own launches of that time are in the scores and were not remembered one
         * by one. Turned off, the bookkeeping goes. Left on, nothing changes. Either way round it is another
         * [SystemCount.period] from then on: a read of Android's record that was under way is not this
         * count's, and its result is dropped ([recordOpenings]).
         */
        fun countingSwitched(prefs: MutablePreferences, was: Boolean, on: Boolean, now: Long) {
            if (was && on) return
            prefs.remove(Keys.OWN_LAUNCHES)
            prefs.remove(Keys.SYSTEM_FRONT)
            if (on) prefs[Keys.SYSTEM_SYNCED_TO] = now else prefs.remove(Keys.SYSTEM_SYNCED_TO)
            if (was != on) prefs[Keys.COUNTING_PERIOD] = (prefs[Keys.COUNTING_PERIOD] ?: 0L) + 1
        }

        /** Android's report of a launch made in the launcher (made when the app's screen is up) comes at most
         *  this long after the launcher's own note of it (the moment the start was asked for), never before. */
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

        /**
         * The stat after an opening at [atMillis] that is counted only now, read from Android's record. One at
         * or after the stored last launch is a launch like any ([applyLaunch]). An OLDER one adds what a launch
         * of its age is worth at the stored time (a half-life before it, half a launch), and that time stays: a
         * score's time never moves back, and an opening weighs the same whether it is read at once or late.
         */
        fun applyOpening(prev: UsageStat?, atMillis: Long): UsageStat {
            if (prev == null || atMillis >= prev.lastUsed) return applyLaunch(prev, atMillis)
            return UsageStat(prev.score + currentScore(UsageStat(1f, atMillis), prev.lastUsed), prev.lastUsed)
        }

        /**
         * The stat after an app was opened at [atMillis], noted at [nowMillis] (the clock as the note is
         * written). An opening older than the stored time is one of two things, and the stored time tells
         * which. Not after [nowMillis]: something later was counted first and this note came late
         * ([applyOpening]: the stored time stays). AFTER [nowMillis]: nothing can have been opened in the
         * future, so the phone's clock was ahead when that was stored and has been set back since. Taken for
         * a late note, a real launch then left the app's time a week ahead and counted for half a launch,
         * every time, until the clock caught up. It is a launch like any ([applyLaunch]): the score is not
         * faded for time that did not pass, and the app's time is the clock's again.
         */
        fun applyNoted(prev: UsageStat?, atMillis: Long, nowMillis: Long): UsageStat =
            if (prev != null && prev.lastUsed > nowMillis) applyLaunch(prev, atMillis) else applyOpening(prev, atMillis)
    }
}
