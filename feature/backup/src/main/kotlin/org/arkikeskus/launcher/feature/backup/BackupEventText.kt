package org.arkikeskus.launcher.feature.backup

import android.content.res.Resources

/** The text [event] shows: on the backup screen's snackbar and in the "Open with" import's dialog. */
internal fun backupEventMessage(res: Resources, event: BackupEvent): String = when (event) {
    is BackupEvent.Exported -> res.getString(R.string.backup_exported)
    is BackupEvent.Restored ->
        if (event.skipped > 0) {
            res.getQuantityString(R.plurals.backup_restored_skipped, event.skipped, event.skipped)
        } else {
            res.getString(R.string.backup_restored)
        }
    BackupEvent.InvalidFile -> res.getString(R.string.backup_import_invalid)
    BackupEvent.TooLarge -> res.getString(R.string.backup_error_too_large)
    BackupEvent.ReadBlocked -> res.getString(R.string.backup_import_read_blocked)
    is BackupEvent.Failed -> {
        val failed = res.getString(R.string.backup_failed)
        if (event.message.isBlank()) failed else "$failed: ${event.message}"
    }
}
