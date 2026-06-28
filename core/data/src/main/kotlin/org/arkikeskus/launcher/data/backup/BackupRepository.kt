package org.arkikeskus.launcher.data.backup

import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.local.HomeItemDao
import javax.inject.Inject
import javax.inject.Singleton

data class RestoreResult(val restored: Int, val skipped: Int)

@Singleton
class BackupRepository @Inject constructor(
    private val homeItemDao: HomeItemDao,
    private val settings: SettingsRepository,
) {
    suspend fun exportDocument(createdAt: Long, appVersion: String): BackupDocument =
        BackupDocument(
            format = BackupCodec.FORMAT,
            appVersion = appVersion,
            createdAt = createdAt,
            settings = settings.exportRaw(),
            homeItems = BackupMapper.toBackupItems(homeItemDao.getAllOnce()),
        )

    suspend fun restoreDocument(
        doc: BackupDocument,
        mainUserSerial: Long,
        installedAppKeys: Set<String>,
        installedPackages: Set<String>,
    ): RestoreResult {
        val mapping = BackupMapper.toEntities(doc.homeItems, mainUserSerial, installedAppKeys, installedPackages)
        homeItemDao.replaceLayout(mapping.entities)
        settings.importRaw(doc.settings)
        return RestoreResult(mapping.entities.size, mapping.skipped)
    }
}
