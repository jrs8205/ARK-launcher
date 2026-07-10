package org.arkikeskus.launcher.data.backup

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Process
import android.os.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import javax.inject.Inject
import javax.inject.Singleton

data class RestoreResult(val restored: Int, val skipped: Int)

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeItemDao: HomeItemDao,
    private val settings: SettingsRepository,
) {
    suspend fun exportDocument(createdAt: Long, appVersion: String): BackupDocument =
        BackupDocument(
            format = BackupCodec.FORMAT,
            appVersion = appVersion,
            createdAt = createdAt,
            settings = settings.exportRaw(),
            homeItems = BackupMapper.toBackupItems(homeItemDao.getAllOnce(), mainUserSerial()),
        )

    suspend fun restoreDocument(
        doc: BackupDocument,
        installedAppKeys: Set<String>,
        installedPackages: Set<String>,
    ): RestoreResult {
        // Items are validated against the grid the backup itself declares — its home_columns is
        // imported right below, so the restored layout and the live grid agree.
        val columns = ((doc.settings["home_columns"] as? Number)?.toInt() ?: 4)
            .coerceIn(SettingsRepository.MIN_COLUMNS, SettingsRepository.MAX_COLUMNS)
        val mapping = BackupMapper.toEntities(
            items = doc.homeItems,
            mainUserSerial = mainUserSerial(),
            installedAppKeys = installedAppKeys,
            installedPackages = installedPackages,
            widgetPackages = widgetPackages(),
            columns = columns,
        )
        val previous = homeItemDao.getAllOnce()
        homeItemDao.replaceLayout(mapping.entities)
        try {
            settings.importRaw(doc.settings)
        } catch (t: Throwable) {
            // The layout was already replaced; put the old one back so a failed settings write
            // never leaves a half-restored home screen (Room and DataStore share no transaction).
            runCatching { homeItemDao.replaceLayout(previous) }
            throw t
        }
        return RestoreResult(mapping.entities.size, mapping.skipped)
    }

    private fun mainUserSerial(): Long = runCatching {
        context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
    }.getOrDefault(0L)

    /** Packages providing app widgets — a widget-only app has no launcher activity, so the
     *  launchable-apps set alone would wrongly drop its restored widgets. */
    private fun widgetPackages(): Set<String> = runCatching {
        AppWidgetManager.getInstance(context).installedProviders
            .map { it.provider.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}
