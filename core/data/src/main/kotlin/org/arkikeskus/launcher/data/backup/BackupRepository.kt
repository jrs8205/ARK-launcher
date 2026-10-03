package org.arkikeskus.launcher.data.backup

import android.appwidget.AppWidgetManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.AppRowResolver
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import org.arkikeskus.launcher.data.local.HomeItemEntity
import javax.inject.Inject
import javax.inject.Singleton

data class RestoreResult(val restored: Int, val skipped: Int)

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeItemDao: HomeItemDao,
    private val settings: SettingsRepository,
    private val resolver: AppRowResolver,
) {
    suspend fun exportDocument(createdAt: Long, appVersion: String): BackupDocument =
        BackupDocument(
            format = BackupCodec.FORMAT,
            appVersion = appVersion,
            createdAt = createdAt,
            settings = settings.exportRaw(),
            homeItems = BackupMapper.toBackupItems(homeItemDao.getAllOnce(), mainUserSerial()),
            mainUserSerial = mainUserSerial(),
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
        val restoredSettings = BackupMapper.remapSettingsProfiles(doc.settings, doc.mainUserSerial, ownSerial)
        // Settings first, then the layout. Room and DataStore share no transaction, so a process
        // death between the two writes leaves half a restore whichever goes first; this order makes
        // it the harmless half. Replacing the layout is the destructive step, so it comes last:
        // until then the current layout is still stored intact (shown under the backup's settings),
        // and restoring again finishes the job. Layout-first wiped the layout and could leave the
        // restored one on the old grid, where items past its columns/rows are invisible rows that
        // block cells. NonCancellable: the caller's scope dies when the user leaves Settings
        // mid-restore, and a cancelled coroutine could neither write the layout nor roll back.
        withContext(NonCancellable) {
            val previousSettings = settings.snapshot()
            val previousLayout = homeItemDao.getAllOnce()
            settings.importRaw(restoredSettings)
            try {
                homeItemDao.replaceLayout(mapping.entities)
            } catch (t: Throwable) {
                // Put the old settings back so a failed layout write never leaves half a restore.
                runCatching { settings.restoreSnapshot(previousSettings) }
                throw t
            }
            repinShortcuts(previousLayout, mapping.entities)
        }
        return RestoreResult(mapping.entities.size, mapping.skipped)
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
    private fun widgetPackages(): Set<String> = runCatching {
        AppWidgetManager.getInstance(context).installedProviders
            .map { it.provider.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}
