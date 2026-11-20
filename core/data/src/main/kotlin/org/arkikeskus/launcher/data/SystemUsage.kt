package org.arkikeskus.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
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
     *  Only the user the launcher runs as: a work profile's and the private space's apps are not in it. */
    fun resumedBetween(fromMs: Long, toMs: Long): List<AppOpening>
}

/** [SystemUsageSource] from Android's usage statistics. */
@Singleton
class AndroidSystemUsageSource @Inject constructor(@ApplicationContext private val context: Context) : SystemUsageSource {
    override fun hasAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    override fun resumedBetween(fromMs: Long, toMs: Long): List<AppOpening> = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = manager.queryEvents(fromMs, toMs) ?: return emptyList()
        val resumed = ArrayList<AppOpening>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                resumed += AppOpening(event.packageName ?: continue, event.className, event.timeStamp)
            }
        }
        resumed
    }.getOrDefault(emptyList())
}

/**
 * One opening per change of the app in front. An app's own screens following one another are one opening (the
 * first of them); the app coming to the front again after another one was there, the launcher included, is a
 * new one. [resumed] is in time order.
 */
fun openingsOf(resumed: List<AppOpening>): List<AppOpening> {
    val openings = ArrayList<AppOpening>()
    var inFront: String? = null
    for (screen in resumed) {
        if (screen.packageName != inFront) openings += screen
        inFront = screen.packageName
    }
    return openings
}

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
 * Read: the last [WINDOW_MS] at most, and never a moment twice (the time read up to is stored). Not counted:
 * the launcher itself, a package the launcher shows no app of, and anything of another user (Android does not
 * tell of a work profile's or the private space's apps here, and [apps] hands over the launcher's own user's
 * apps only). Nothing leaves the phone: the result is a score per app in the launcher's settings file, as
 * the launcher's own count always was.
 */
@Singleton
class SystemUsageSync internal constructor(
    private val source: SystemUsageSource,
    private val usage: AppUsageRepository,
    private val enabled: suspend () -> Boolean,
    /** The apps an opening may count for: the launcher's own user's, never the private space's. */
    private val apps: suspend () -> List<AppItem>,
    private val ownPackage: String,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        source: SystemUsageSource,
        usage: AppUsageRepository,
        settings: SettingsRepository,
        appRepository: AppRepository,
        resolver: AppRowResolver,
    ) : this(
        source = source,
        usage = usage,
        enabled = { settings.settings.first().countSystemUsage },
        apps = {
            val own = resolver.ownUserSerial()
            appRepository.apps.first().filter { it.userSerial == own }
        },
        ownPackage = context.packageName,
    )

    /** One sync at a time: two at once would both read from the same stored time and count twice. */
    private val running = Mutex()

    /** Counts what came to the front since the last sync, up to [now]. Does nothing while the switch is off or
     *  access is not given; the stored time then stays, and at most [WINDOW_MS] is read once it is on. */
    suspend fun sync(now: Long = System.currentTimeMillis()) = running.withLock {
        if (!enabled() || !source.hasAccess()) return@withLock
        val from = maxOf(usage.systemSyncedTo.first(), now - WINDOW_MS)
        if (from >= now) return@withLock
        val known = apps()
        val counted = openingsOf(source.resumedBetween(from, now))
            .filter { it.packageName != ownPackage }
            .mapNotNull { opening -> appKeyFor(opening, known)?.let { it to opening.timeMs } }
        usage.recordOpenings(counted, syncedTo = now)
    }

    companion object {
        /** How far back the first sync reads (and any sync after a long pause): the score's own half-life. */
        const val WINDOW_MS = AppUsageRepository.HALF_LIFE_MS
    }
}
