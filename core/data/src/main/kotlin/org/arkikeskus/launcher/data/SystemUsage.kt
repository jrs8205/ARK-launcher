package org.arkikeskus.launcher.data

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.UserHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

/** An app's screen that came to the front: its package, the activity, and when (epoch ms). */
data class AppOpening(val packageName: String, val className: String?, val timeMs: Long)

/**
 * What Android itself knows of apps coming to the front, which it tells only to an app the user has given
 * "usage access" in Android's settings (it cannot be asked for in a dialog). The launcher reads it for one
 * thing, and only while the user has switched that on: counting an app as opened when it was opened from
 * somewhere else than the launcher (a notification, a widget, another app) for the "most used" card.
 */
interface SystemUsageSource {
    /** Whether the user has given the launcher usage access. */
    fun hasAccess(): Boolean

    /** The screens that came to the front between [fromMs] and [toMs], oldest first; nothing without access.
     *  Only the user the launcher runs as: a work profile's and the private space's apps are not in it.
     *  Null when Android could not be asked or did not answer (the service is missing, the call threw, the
     *  user is still locked after a restart): that is not "nothing came to the front", and the caller must
     *  not take the span for read. */
    fun resumedBetween(fromMs: Long, toMs: Long): List<AppOpening>?
}

/** [SystemUsageSource] from Android's usage statistics. */
@Singleton
class AndroidSystemUsageSource @Inject constructor(@ApplicationContext private val context: Context) : SystemUsageSource {
    override fun hasAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    // The permission is declared in the app's manifest (this module declares none of the launcher's own). It
    // is a special access, not a runtime permission: without it Android answers with nothing. A refusal
    // thrown instead, a missing service and no answer at all are no answer: null, never the empty list.
    //
    // Nor is an answer with NO EVENT OF ANY KIND in it one to go by. Android's own query hands back the same
    // empty answer for a span in which nothing happened as when its service did not answer or the call to it
    // failed (and while the user is still locked after a restart), and the two cannot be told apart here. In a
    // span with no event at all nothing came to the front, so leaving it to be read again loses nothing; taking
    // a failure for "read" would lose what was opened in it. An answer with events but none of a screen coming
    // to the front is an answer: the empty list.
    @SuppressLint("MissingPermission")
    override fun resumedBetween(fromMs: Long, toMs: Long): List<AppOpening>? = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = manager.queryEvents(fromMs, toMs) ?: return null
        if (!events.hasNextEvent()) return null
        val resumed = ArrayList<AppOpening>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                resumed += AppOpening(event.packageName ?: continue, event.className, event.timeStamp)
            }
        }
        resumed
    }.getOrNull()
}

/**
 * One opening per change of the app in front. An app's own screens following one another are one opening (the
 * first of them); the app coming to the front again after another one was there, the launcher included, is a
 * new one. [resumed] is in time order. [inFront] is the package that was in front before the first of them
 * (where the read before this one ended), null when that is not known: its next screen is no opening either.
 */
fun openingsOf(resumed: List<AppOpening>, inFront: String? = null): List<AppOpening> {
    val openings = ArrayList<AppOpening>()
    var front = inFront
    for (screen in resumed) {
        if (screen.packageName != front) openings += screen
        front = screen.packageName
    }
    return openings
}

/**
 * The apps of [all] an opening may count for: those of [user], the user the launcher runs as (Android tells of
 * no other user's here). Null when there is none: the launcher's own user always has apps, so an empty list is
 * a listing that failed or has not come, and nothing can be told from it of what was opened.
 */
internal fun appsOfUser(all: List<AppItem>, user: UserHandle = Process.myUserHandle()): List<AppItem>? =
    all.filter { it.user == user }.takeIf { it.isNotEmpty() }

/**
 * The app of [apps] that [opening] counts for: the launcher entry that IS the screen that came to the front
 * when there is one (an app with two entries, each its own), else the package's first entry by class name
 * (a screen deep inside the app, opened from a notification). Null for a package with no entry in [apps]:
 * nothing the launcher shows. [apps] is what may be counted at all: the caller leaves out other users' apps.
 */
fun appKeyFor(opening: AppOpening, apps: List<AppItem>): String? {
    val ofPackage = apps.filter { it.packageName == opening.packageName }
    return (ofPackage.firstOrNull { it.className == opening.className } ?: ofPackage.minByOrNull { it.className })?.key
}

/**
 * Brings the "most used" scores up to date with what Android saw ([SystemUsageSource]): while the user has
 * switched that on and usage access is given, every app that came to the front since the last time is counted
 * as one opening, in the same score the launcher's own launches go to ([AppUsageRepository]). Run when the
 * app drawer opens, which is when the card is looked at.
 *
 * Read: from the moment the user switched it on, the last [WINDOW_MS] at most, and never a moment twice (the
 * time read up to is stored). Not counted: the launcher itself, a package the launcher shows no app of (nor
 * is its screen "another app in front": the app under a dialog of the phone's own is opened once), and
 * anything of another user (Android does not tell of a work profile's or the private space's apps here, and
 * [apps] hands over the launcher's own user's apps only). A launch made in the launcher is in Android's record
 * too and in the score already: [AppUsageRepository.recordOpenings] leaves that report out. Nothing leaves the
 * phone: the result is a score per app in the launcher's settings file, as the launcher's own count always was.
 */
@Singleton
class SystemUsageSync internal constructor(
    private val source: SystemUsageSource,
    private val usage: AppUsageRepository,
    /** The apps an opening may count for: the launcher's own user's, never the private space's. Null when
     *  they cannot be told right now ([appsOfUser]). */
    private val apps: suspend () -> List<AppItem>?,
    private val ownPackage: String,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        source: SystemUsageSource,
        usage: AppUsageRepository,
        appRepository: AppRepository,
    ) : this(
        source = source,
        usage = usage,
        apps = { appsOfUser(appRepository.apps.first()) },
        ownPackage = context.packageName,
    )

    /** One sync at a time: two at once would both read from the same stored time and count twice. */
    private val running = Mutex()

    /**
     * Counts what came to the front since the last sync, up to [now]. Does nothing while the switch is off or
     * access is not given; the stored time then stays (the moment the switch was turned on, before the first
     * read), and at most [WINDOW_MS] is read once the access is there.
     *
     * The read is of ONE count: where it stands is read first, in one piece ([AppUsageRepository.systemCount]),
     * and the result is stored only if that is still the count that is on
     * ([AppUsageRepository.recordOpenings]). The switch can be turned while the launcher's apps are waited
     * for: Android is ASKED only for a count that is still on when the wait is over (what the privacy text
     * promises is about the reading, not only about what is kept). And only a read that was made moves the
     * time on: when the launcher's apps cannot be told, or Android does not answer, nothing is stored and the
     * same span is read again at the next sync.
     *
     * [now] is for a test; left out, the clock is read HERE, with the lock held. Syncs go one after another,
     * so a stored time later than this moment can only mean that the phone's clock was set back since: the
     * reading then starts anew at this moment ([AppUsageRepository.restartCount]). Without that every sync
     * waited for the old, later time to come again, a week for a clock that had been a week ahead.
     */
    suspend fun sync(now: Long? = null) = running.withLock {
        val count = usage.systemCount.first() ?: return@withLock
        if (!source.hasAccess()) return@withLock
        val at = now ?: System.currentTimeMillis()
        if (count.syncedTo <= 0L) {
            // On, but no start was noted (the setting's own switch notes one): the count starts here.
            // Nothing is read back, as the launcher's own launches before this moment were not remembered.
            usage.recordOpenings(count, emptyList(), syncedTo = at, inFront = null)
            return@withLock
        }
        if (count.syncedTo > at) {
            usage.restartCount(count, at)
            return@withLock
        }
        val from = maxOf(count.syncedTo, at - WINDOW_MS)
        // This very moment is read already.
        if (from >= at) return@withLock
        val known = apps() ?: return@withLock
        if (usage.systemCount.first() != count) return@withLock
        val resumed = source.resumedBetween(from, at) ?: return@withLock
        // Only the launcher and what it shows an app of can be "the app in front": a screen of anything else
        // (a permission dialog of the phone's own, the share sheet, the recents screen) lies over an app, and
        // the app coming back from under it was not opened again.
        val shown = known.mapTo(HashSet()) { it.packageName }
        val screens = resumed.filter { it.packageName == ownPackage || it.packageName in shown }
        // The app in front where the read before this one ended is still in front where this one begins: its
        // next screen is no new opening. Not so over a stretch that was never read (a pause longer than the
        // window): what was in front before it says nothing of now.
        val before = count.inFront.takeIf { from == count.syncedTo }
        val entries = known.mapTo(HashSet()) { it.packageName to it.className }
        val counted = openingsOf(screens, inFront = before)
            .filter { it.packageName != ownPackage }
            .mapNotNull { opening ->
                val key = appKeyFor(opening, known) ?: return@mapNotNull null
                CountedOpening(key, opening.timeMs, exact = (opening.packageName to opening.className) in entries)
            }
        usage.recordOpenings(count, counted, syncedTo = at, inFront = screens.lastOrNull()?.packageName ?: before)
    }

    companion object {
        /** How far back a sync reads at most (the first one after the access was given late, or one after a
         *  long pause): the score's own half-life. */
        const val WINDOW_MS = AppUsageRepository.HALF_LIFE_MS
    }
}
