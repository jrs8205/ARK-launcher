package org.arkikeskus.launcher.data.backup

/** One backed-up home-layout row. Widgets are excluded; spans are always 1×1 on restore. */
data class BackupItem(
    val id: Long,
    val containerId: Long,
    val folderName: String?,
    val packageName: String,
    val className: String,
    val mainProfile: Boolean,
    val shortcutId: String?,
    val page: Int,
    val cellX: Int,
    val cellY: Int,
)

/** The full backup payload — identical for file export and Drive. */
data class BackupDocument(
    val format: Int,
    val appVersion: String,
    val createdAt: Long,
    val settings: Map<String, Any>,
    val homeItems: List<BackupItem>,
)

class BackupFormatException(message: String) : Exception(message)
