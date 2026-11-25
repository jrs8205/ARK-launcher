package org.arkikeskus.launcher.feature.backup

import android.content.res.Resources
import org.arkikeskus.launcher.data.LastBackup
import org.arkikeskus.launcher.data.backup.ExportProfileChangedException
import org.arkikeskus.launcher.ui.shownProfileName

/** With several profiles the button and the time both name the profile from the same settings reading. */
internal fun exportedProfileName(res: Resources, last: LastBackup?): String? =
    last?.profileName?.let { shownProfileName(res, it) }

internal fun exportButtonText(res: Resources, last: LastBackup?): String {
    val profile = exportedProfileName(res, last) ?: return res.getString(R.string.backup_export)
    return res.getString(R.string.backup_export_profile, profile)
}

internal fun lastBackupText(res: Resources, last: LastBackup?, format: (Long) -> String): String? {
    if (last == null || last.timeMs <= 0L) return null
    val formatted = format(last.timeMs)
    val profile = exportedProfileName(res, last) ?: return res.getString(R.string.backup_last_time, formatted)
    return res.getString(R.string.backup_last_time_profile, profile, formatted)
}

/** What the screen shows for an export that failed with [error]. */
internal fun exportFailureEvent(error: Throwable): BackupEvent = when (error) {
    is ExportProfileChangedException -> BackupEvent.ExportProfileChanged
    else -> BackupEvent.Failed(error.message.orEmpty())
}
