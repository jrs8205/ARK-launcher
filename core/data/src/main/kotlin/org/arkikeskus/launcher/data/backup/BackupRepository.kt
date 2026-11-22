package org.arkikeskus.launcher.data.backup

import android.appwidget.AppWidgetManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.DrawerWallpaperStore
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.WidgetIdReleaser
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.profile.HomeProfileStore
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.ScreensaverSettingsStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a restore did: the rows it [restored], the ones it [skipped] (their app is not installed), and how many
 * other home profiles are still listed although their rows went ([profilesLeft]: the list could not be
 * written after the layout was replaced; they are empty and can be deleted in Settings).
 *
 * [picturesLeft]: of how many home profiles that went the picture behind the app drawer is still on the disk
 * (it could not be removed). The layout is restored all the same; what is left is found by the next restore
 * as a home screen no profile is listed for, and removed then.
 */
data class RestoreResult(val restored: Int, val skipped: Int, val profilesLeft: Int = 0, val picturesLeft: Int = 0)

/**
 * The home profiles as a restore's question showed them: the one that is on ([activeId]), which the restore
 * fills with the backup; the others the list names ([otherIds]), which it deletes and which the question
 * named one by one; and the ids something is kept under though the list does not name them ([unlistedIds]:
 * a home screen's rows, a profile's own values or its drawer picture, left by a damaged settings file, a
 * copy that died or a restore that died), which go too and of which the question had its own sentence.
 *
 * The two sets are agreed to APART and are compared apart. An id can move from one to the other between the
 * question and the restore (what an attempt left under an id is passed over when the next profile is made,
 * so a new, named profile can take that id): compared as one set, "what is left under id 2 goes" was taken
 * for leave to delete the profile made under id 2 since.
 */
data class ConfirmedProfiles(val activeId: Long, val otherIds: Set<Long>, val unlistedIds: Set<Long> = emptySet())

/**
 * The home profile an export wrote (a backup file holds one profile, the one that is on): its [id], its stored
 * [name] (empty for one the user never named), and whether it is [oneOfSeveral]: only then does the screen
 * need to say which profile the file is of.
 */
data class ExportedProfile(val id: Long, val name: String, val oneOfSeveral: Boolean)

/** The home profile that is on is not the one the export was asked for (the one its button named): nothing
 *  was written. */
class ExportProfileChangedException : Exception("The home profile changed after the export was asked for")

/** The profiles are not the ones the restore was agreed to with ([ConfirmedProfiles]): nothing was written. */
class ProfilesChangedException : Exception("The home profiles changed after the restore was confirmed")

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeItemDao: HomeItemDao,
    private val settings: SettingsRepository,
    private val resolver: AppRowResolver,
    private val screensaver: ScreensaverSettingsStore,
    private val profiles: HomeProfileStore? = null,
    private val drawerPictures: DrawerWallpaperStore? = null,
    private val widgetIds: WidgetIdReleaser? = null,
) {
    /** Runs [work] while the home profile that is on stays on (a switch asked for meanwhile waits). */
    private suspend fun <T> whileProfileStays(work: suspend () -> T): T = profiles?.whileActiveStays { work() } ?: work()

    /** The settings and the rows of the profile that is on: of ONE profile, so the two are read while it
     *  stays on (a switch between them gave one profile's grid with another's rows). */
    suspend fun exportDocument(createdAt: Long, appVersion: String): BackupDocument =
        whileProfileStays { document(createdAt, appVersion) }

    /** The document of the profile that is on, for a caller that holds it ([whileProfileStays]). */
    private suspend fun document(createdAt: Long, appVersion: String) = BackupDocument(
        format = BackupCodec.FORMAT,
        appVersion = appVersion,
        createdAt = createdAt,
        settings = settings.exportRaw(),
        homeItems = BackupMapper.toBackupItems(homeItemDao.getAllOnce(), mainUserSerial()),
        mainUserSerial = mainUserSerial(),
        screensaver = screensaver.exportRaw(),
    )

    /**
     * Exports the home profile that is on: its document ([exportDocument]) is handed to [write], and once that
     * has returned [createdAt] is noted as THAT profile's last export. Returns the profile the file is of, for
     * the screen to name.
     *
     * The profile stays on only while the document is read and whose it is is noted. [write] runs AFTER the
     * hold has ended: it hands the document to a file provider that may wait on a network without a limit, and
     * everything that needs the profile (a switch, a page operation, a pin request, a restore) would wait with
     * it. The time is therefore noted by the profile's id, whichever profile is on by then, and not at all for
     * a profile deleted meanwhile.
     *
     * [expectedProfile] is the profile the export was asked for (the one its button named): with another one
     * on, nothing is written and [ExportProfileChangedException] is thrown before [write] is called. What
     * [write] throws is thrown, with no time noted. That the time itself cannot be noted does not undo the
     * export: the file is written.
     *
     * A write can be slow, so another export can be asked for while one is still being written. [target] is
     * where this one writes (the picked document; any value that is equal for the same place): exports to the
     * SAME place go one after another, each reading its document only when the one before has finished, so the
     * place ends up holding the later export and not whichever was written slowest. Exports to different places
     * do not wait for one another (a place that never answers must not stop every other export), and of those
     * the time noted is that of the export whose document was read LAST: an older one finishing afterwards does
     * not put its time over it. No profile is held while any of this waits.
     *
     * [createdAt] is the clock, read when the export's document is read, with its place's lock held: the
     * document, the export's number and the time that is noted are then of one moment. Read by the caller
     * before the wait for the place, an export that had waited for a slow one carried an old time and the
     * highest number, and put that old time over a later export's.
     */
    suspend fun export(
        createdAt: () -> Long,
        appVersion: String,
        expectedProfile: Long? = null,
        target: Any? = null,
        write: suspend (BackupDocument) -> Unit,
    ): ExportedProfile {
        val place = target?.let { exportPlaces.computeIfAbsent(it) { Mutex() } } ?: return exportNow(createdAt, appVersion, expectedProfile, write)
        return place.withLock { exportNow(createdAt, appVersion, expectedProfile, write) }
    }

    /** One export, for a caller that has its place to itself ([export]). */
    private suspend fun exportNow(
        createdAt: () -> Long,
        appVersion: String,
        expectedProfile: Long?,
        write: suspend (BackupDocument) -> Unit,
    ): ExportedProfile {
        val read = whileProfileStays {
            val active = homeItemDao.resolvedProfile()
            if (expectedProfile != null && active != expectedProfile) throw ExportProfileChangedException()
            val list = profiles?.list().orEmpty()
            val exported = ExportedProfile(active, list.firstOrNull { it.id == active }?.name.orEmpty(), list.size > 1)
            // Timed and numbered as its document is read, under the hold: the higher number has the later
            // document and the later time.
            val at = createdAt()
            Triple(document(at, appVersion), exported, exportsRead.incrementAndGet() to at)
        }
        val (document, exported, order) = read
        val (number, at) = order
        // The hold has ended: the file is written with nothing of the launcher's waiting on it.
        write(document)
        timeNoting.withLock {
            if (number > (timeNotedFor[exported.id] ?: 0L)) {
                runCatching { settings.setLocalLastBackup(exported.id, at) }.onSuccess { timeNotedFor[exported.id] = number }
            }
        }
        return exported
    }

    /** The lock of each place an export has been written to in this process ([export]); a handful at most. */
    private val exportPlaces = ConcurrentHashMap<Any, Mutex>()

    /** How many exports' documents have been read: each export's number, in the order they were read. */
    private val exportsRead = AtomicLong()

    /** The number of the export whose time is noted for each home profile, changed under [timeNoting]. By
     *  number and not by the times themselves: a clock that was set back must not keep a later export's time
     *  from being noted. */
    private val timeNotedFor = HashMap<Long, Long>()
    private val timeNoting = Mutex()

    /** Restores [doc] against the launchable apps on this device ([installedApps]: AppItem keys,
     *  "package/class/userSerial", from every profile). Blocking system queries: call off the main
     *  thread. [confirmed] is what the user agreed to: with other profiles than those there are when the
     *  restore comes to write, it writes nothing and throws [ProfilesChangedException]. */
    suspend fun restoreDocument(
        doc: BackupDocument,
        installedApps: Collection<String>,
        confirmed: ConfirmedProfiles? = null,
    ): RestoreResult {
        val ownSerial = mainUserSerial()
        // Rows restore into the launcher's own profile, so only that profile's apps count.
        val installed = BackupMapper.installedInProfile(installedApps, ownSerial)
        // Items are validated against the grid the backup itself declares — its home_columns and
        // home_rows are imported right below, so the restored layout and the live grid agree.
        val columns = ((doc.settings["home_columns"] as? Number)?.toInt() ?: 4)
            .coerceIn(SettingsRepository.MIN_COLUMNS, SettingsRepository.MAX_COLUMNS)
        val gridRows = ((doc.settings["home_rows"] as? Number)?.toInt() ?: HomeLayoutRepository.ROWS)
            .coerceIn(SettingsRepository.MIN_ROWS, SettingsRepository.MAX_ROWS)
        val mapping = BackupMapper.toEntities(
            items = doc.homeItems,
            mainUserSerial = ownSerial,
            installedAppKeys = installed.appKeys,
            installedPackages = installed.packages,
            widgetPackages = widgetPackages(),
            columns = columns,
            gridRows = gridRows,
            missingShortcuts = missingShortcuts(doc.homeItems, installed.packages, ownSerial),
        )
        val remapped = BackupMapper.remapSettingsProfiles(doc.settings, doc.mainUserSerial, ownSerial)
        // The dock travels as rows since format 4. An older file brings it as the favourites key
        // list: those become dock rows here, and the key never reaches the settings (a file of the
        // new format that still carries one is a hand-edited mix; its rows win).
        val restoredSettings = remapped - LEGACY_DOCK_KEY
        val legacyDock = remapped[LEGACY_DOCK_KEY] as? String
        val entities = if (!legacyDock.isNullOrBlank() && mapping.entities.none { it.containerId == HomeItemEntity.DOCK }) {
            mapping.entities + BackupMapper.legacyDockEntities(legacyDock, ownSerial, installed.appKeys)
        } else {
            mapping.entities
        }
        // Settings first, then the layout. Room and DataStore share no transaction, so a process
        // death between the two writes leaves half a restore whichever goes first; this order makes
        // it the harmless half. Replacing the layout is the destructive step, so it comes last:
        // until then the current layout is still stored intact (shown under the backup's settings),
        // and restoring again finishes the job. Layout-first wiped the layout and could leave the
        // restored one on the old grid, where items past its columns/rows are invisible rows that
        // block cells. NonCancellable: the caller's scope dies when the user leaves Settings
        // mid-restore, and a cancelled coroutine could neither write the layout nor roll back.
        // Under the pin lock: the re-pin below replaces whole system pin sets, so a shortcut pinned
        // or removed meanwhile must wait for it instead of being overwritten by this snapshot.
        // While the profile that is on stays on: the settings, the rows and the list of profiles this
        // leaves are of one profile, and a switch in the middle put the rows in one and left another.
        val (profilesLeft, picturesLeft) = withContext(NonCancellable) { whileProfileStays { ShortcutPinLock.withLock {
            // The profiles the user agreed to lose are the profiles there are, or nothing is written: the file
            // was read after the question, and a profile made meanwhile would go without a word. Under the
            // hold no profile is made, deleted or switched to, so what is checked here is what is replaced.
            // And the ids something is kept under though the list does not name them: the rows below come
            // with the ids their backup gave them, so EVERY row there is goes first, whoever's it is. A home
            // screen whose profile the list had lost went then without ever having been in the question.
            // Not the rows alone: what a restore that died after its rows left of such a profile (its own
            // values, its picture) is found by this too, so the next restore names it and finishes the job.
            // Each set against its own ([ConfirmedProfiles]): a listed profile is never deleted on the
            // word given for something unlisted, nor the other way round.
            var unlisted = emptySet<Long>()
            if (confirmed != null && profiles != null) {
                val active = profiles.current()
                val listed = profiles.list().map { it.id }.toSet() - active
                val kept = homeItemDao.profileIds().toSet() + profiles.unlistedStores() + drawerPictures?.profilesWithPictures().orEmpty()
                unlisted = kept - active - listed
                if (active != confirmed.activeId || listed != confirmed.otherIds || unlisted != confirmed.unlistedIds) {
                    throw ProfilesChangedException()
                }
            }
            val previousSettings = settings.snapshot()
            // The rows of EVERY home profile: a restore replaces them all (what a backup file describes
            // is one profile), and the pinned shortcuts they held are released below.
            val previousLayout = homeItemDao.getAllOfEveryProfile()
            settings.importRaw(restoredSettings)
            val importedSettings = settings.snapshot()
            // The screensaver's settings are a file of their own, written with the launcher's. A file
            // without the section (a backup from before the screensaver) leaves them alone entirely.
            var screensaverImport: ScreensaverSettingsStore.Import? = null
            try {
                // Throws when its file cannot be saved, having undone itself: the layout is then never touched.
                screensaverImport = doc.screensaver?.let { screensaver.importRaw(screensaverForThisPhone(it)) }
                homeItemDao.replaceEveryProfile(entities)
            } catch (t: Throwable) {
                // Take both imports back so a failed write never leaves half a restore. Only what the
                // imports themselves changed: a setting the user changed meanwhile keeps its newer value.
                runCatching { settings.rollbackImport(previousSettings, importedSettings) }
                screensaverImport?.let { done -> runCatching { screensaver.rollbackImport(done) } }
                throw t
            }
            // The widgets of the profiles that were not on went with their rows, and no home screen shows
            // them: none would free their ids before the next start, and their providers would go on
            // updating widgets nobody sees. An id a restored row holds stays (a backup carries none, so
            // today that is no id). The ids of the profile that is on are left to the home screen, whose
            // host holds their views: it frees what no row holds when its rows change.
            widgetIds?.let { releaser ->
                val active = homeItemDao.resolvedProfile()
                val held = entities.mapNotNullTo(HashSet()) { it.appWidgetId }
                previousLayout.filter { it.homeProfileId != active }
                    .mapNotNull { it.appWidgetId }
                    .filter { it !in held }
                    .distinct()
                    .forEach { id -> runCatching { releaser.release(id) } }
            }
            // The layout is the restored profile's alone now; the other profiles' settings go with their
            // rows. Last: until here a failure leaves every profile as it was. A list that cannot be
            // written here leaves the others listed, with no rows: empty profiles the user can delete, and
            // the result says how many, since "restored" alone would call this done.
            val kept = runCatching { profiles?.keepOnlyActive() }
            val removed = kept.getOrNull().orEmpty()
            val left = if (kept.isSuccess) 0 else runCatching { profiles?.list()?.size?.minus(1) }.getOrNull() ?: 1
            // Their own pictures behind the app drawer go with them: nothing in the launcher could remove
            // the picture of a profile that is not there any more. And what was left of the profiles the
            // list had lost, whose home screens went with the user's word: their own values and their
            // pictures. Nothing names them any more.
            if (unlisted.isNotEmpty()) runCatching { profiles?.forgetUnlisted(unlisted) }
            // A picture that could not be removed is still on the disk, under the id of a profile that is
            // gone: counted, so the result does not call the clearing done. The next restore finds each as
            // a home screen no profile is listed for, asks, and tries again.
            val gone = (removed + unlisted).distinct()
            val notCleared = gone.filterNot { id -> pictureCleared(id) }
            // Counted by what is still THERE, read after the removals: a picture that went while its emptied
            // folder stayed is not a picture left (the next restore would find none to ask about). Where
            // the pictures cannot be listed, the removals that did not end clean are what is known.
            val pictures = runCatching { drawerPictures?.profilesWithPictures() }.getOrNull()
                ?.let { still -> gone.count { it in still } }
                ?: notCleared.size
            repinShortcuts(previousLayout, entities)
            left to pictures
        } } }
        return RestoreResult(entities.size, mapping.skipped, profilesLeft, picturesLeft)
    }

    /** Whether nothing is left of the drawer picture of the home profile [id]: its folder went, or there was
     *  none. */
    private suspend fun pictureCleared(id: Long): Boolean =
        runCatching { drawerPictures?.delete(id) ?: true }.getOrDefault(false)

    private fun mainUserSerial(): Long = resolver.ownUserSerial() ?: 0L

    /** The backed-up pinned shortcuts (package to id) the system definitively doesn't have for this
     *  launcher's profile: a pin made by another device's launcher can't be carried over, and such
     *  a row would never render. Unanswerable queries (not the default home app yet) drop nothing;
     *  the startup sweep re-checks those rows later. */
    private fun missingShortcuts(items: List<BackupItem>, packages: Set<String>, userSerial: Long): Set<Pair<String, String>> =
        items.filter { it.mainProfile && it.containerId == HomeItemEntity.HOME && it.packageName in packages }
            .mapNotNull { item -> item.shortcutId?.let { item.packageName to it } }
            .groupBy({ it.first }, { it.second })
            .flatMap { (pkg, ids) -> resolver.missingShortcuts(pkg, userSerial, ids.distinct()).map { pkg to it } }
            .toSet()

    /** Makes the system pin sets match the restored shortcut rows, the invariant removeShortcut
     *  keeps: restored shortcuts get pinned to this launcher, and the pins of shortcut rows the
     *  restore replaced are released. Best effort — a refusal leaves the rows as they are. */
    private fun repinShortcuts(before: List<HomeItemEntity>, after: List<HomeItemEntity>) {
        val pinned = after.filter { it.isShortcut && it.containerId == HomeItemEntity.HOME }
            .groupBy({ it.packageName to it.userSerial }, { it.shortcutId!! })
        val owners = before.filter { it.isShortcut }.map { it.packageName to it.userSerial }.toSet() + pinned.keys
        for (owner in owners) resolver.pinShortcuts(owner.first, owner.second, pinned[owner].orEmpty().distinct())
    }

    /** Packages providing app widgets — a widget-only app has no launcher activity, so the
     *  launchable-apps set alone would wrongly drop its restored widgets. */
    private companion object {
        /** The dock of versions before 0.9.7 (backup format <= 3): a newline-joined app key list. */
        const val LEGACY_DOCK_KEY = "dock_favorites"
    }

    private fun widgetPackages(): Set<String> = runCatching {
        AppWidgetManager.getInstance(context).installedProviders
            .map { it.provider.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * The backup's screensaver settings as this phone can take them: the battery-full notification needs the
     * permission to send notifications, which the phone the backup came from held and this one may not. Its
     * switch is then restored off, so the page never shows a switch that is on and sends nothing; turning it
     * on there asks for the permission.
     */
    private fun screensaverForThisPhone(values: Map<String, Any>): Map<String, Any> {
        val key = DreamPreferences.KEY_BATTERY_FULL_NOTIFICATION
        return if (values[key] == true && !maySendNotifications()) values + (key to false) else values
    }

    private fun maySendNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
