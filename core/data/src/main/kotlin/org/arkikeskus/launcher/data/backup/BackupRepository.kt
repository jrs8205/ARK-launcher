package org.arkikeskus.launcher.data.backup

import android.appwidget.AppWidgetManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.ShortcutPinLock
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.profile.HomeProfileStore
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.ScreensaverSettingsStore
import javax.inject.Inject
import javax.inject.Singleton

data class RestoreResult(val restored: Int, val skipped: Int)

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeItemDao: HomeItemDao,
    private val settings: SettingsRepository,
    private val resolver: AppRowResolver,
    private val screensaver: ScreensaverSettingsStore,
    private val profiles: HomeProfileStore? = null,
) {
    suspend fun exportDocument(createdAt: Long, appVersion: String): BackupDocument =
        BackupDocument(
            format = BackupCodec.FORMAT,
            appVersion = appVersion,
            createdAt = createdAt,
            settings = settings.exportRaw(),
            homeItems = BackupMapper.toBackupItems(homeItemDao.getAllOnce(), mainUserSerial()),
            mainUserSerial = mainUserSerial(),
            screensaver = screensaver.exportRaw(),
        )

    /** Restores [doc] against the launchable apps on this device ([installedApps]: AppItem keys,
     *  "package/class/userSerial", from every profile). Blocking system queries: call off the main
     *  thread. */
    suspend fun restoreDocument(doc: BackupDocument, installedApps: Collection<String>): RestoreResult {
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
        withContext(NonCancellable) { ShortcutPinLock.withLock {
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
            // The layout is the restored profile's alone now; the other profiles' settings go with their
            // rows. Last, and best effort: until here a failure leaves every profile as it was, and a
            // profile left listed by a failure here is an empty one the user can delete.
            runCatching { profiles?.keepOnlyActive() }
            repinShortcuts(previousLayout, entities)
        } }
        return RestoreResult(entities.size, mapping.skipped)
    }

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
