package org.arkikeskus.launcher.data

import android.annotation.SuppressLint
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

    // The permission is declared in the app's manifest (this module declares none of the launcher's own). It
    // is a special access, not a runtime permission: without it Android answers with nothing, and a refusal
    // thrown instead ends in the empty list too.
    @SuppressLint("MissingPermission")
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
     *  access is not given; the stored time then stays (the moment the switch was turned on, before the first
     *  read), and at most [WINDOW_MS] is read once the access is there. */
    suspend fun sync(now: Long = System.currentTimeMillis()) = running.withLock {
        if (!enabled() || !source.hasAccess()) return@withLock
        val syncedTo = usage.systemSyncedTo.first()
        if (syncedTo <= 0L) {
            // On, but no start was noted (the setting's own switch notes one): the count starts here.
            // Nothing is read back, as the launcher's own launches before this moment were not remembered.
            usage.recordOpenings(emptyList(), syncedTo = now)
            return@withLock
        }
        val from = maxOf(syncedTo, now - WINDOW_MS)
        if (from >= now) return@withLock
        val known = apps()
        // Only the launcher and what it shows an app of can be "the app in front": a screen of anything else
        // (a permission dialog of the phone's own, the share sheet, the recents screen) lies over an app, and
        // the app coming back from under it was not opened again.
        val shown = known.mapTo(HashSet()) { it.packageName }
        val screens = source.resumedBetween(from, now).filter { it.packageName == ownPackage || it.packageName in shown }
        val counted = openingsOf(screens)
            .filter { it.packageName != ownPackage }
            .mapNotNull { opening -> appKeyFor(opening, known)?.let { it to opening.timeMs } }
        usage.recordOpenings(counted, syncedTo = now)
    }

    companion object {
        /** How far back a sync reads at most (the first one after the access was given late, or one after a
         *  long pause): the score's own half-life. */
        const val WINDOW_MS = AppUsageRepository.HALF_LIFE_MS
    }
}
