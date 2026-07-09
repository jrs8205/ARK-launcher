package org.arkikeskus.launcher.data.backup

/**
 * One backed-up home-layout row. Widgets ARE included (format 2+): [widgetProvider] is the provider's
 * flattened ComponentName and [spanX]/[spanY] its size — the device-local `appWidgetId` is NOT stored,
 * so a restored widget is re-bound to a fresh id on the target device. Non-widget rows have
 * [widgetProvider] = null and 1×1 spans.
 */
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
    val spanX: Int = 1,
    val spanY: Int = 1,
    val widgetProvider: String? = null,
    /** Non-null → a built-in launcher widget (e.g. "smartspace") occupying spanX×spanY (format 3+). */
    val builtinType: String? = null,
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
