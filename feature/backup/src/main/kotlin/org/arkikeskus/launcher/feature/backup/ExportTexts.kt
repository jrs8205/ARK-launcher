package org.arkikeskus.launcher.feature.backup

import android.content.res.Resources
import org.arkikeskus.launcher.data.LastBackup
import org.arkikeskus.launcher.data.backup.ExportProfileChangedException

/** [name] as a profile is shown everywhere: one the user never named has an empty stored name. */
internal fun shownProfileName(res: Resources, name: String): String =
    name.ifBlank { res.getString(org.arkikeskus.launcher.ui.R.string.profile_default_name) }

/**
 * The name the backup screen says the exported profile by, or null when it need not say one. A backup file
 * holds ONE home profile, the one that is on: with several profiles the export button, its result and the
 * time of the last export name that profile, so nobody takes the file for a backup of everything. With one
 * profile ([profiles] has no other) there is nothing to tell apart, and the texts are what they always were.
 * Null too while the profiles have not been read. A profile the user never named goes under the name it is
 * shown with everywhere.
 */
internal fun exportedProfileName(res: Resources, profiles: RestoreProfiles?): String? =
    profiles?.takeIf { it.deleted.isNotEmpty() }?.let { shownProfileName(res, it.activeName) }

/** The export button's text: which profile it exports, when there are several ([exportedProfileName]). */
internal fun exportButtonText(res: Resources, profiles: RestoreProfiles?): String {
    val profile = exportedProfileName(res, profiles) ?: return res.getString(R.string.backup_export)
    return res.getString(R.string.backup_export_profile, profile)
}

/**
 * When the profile that is on was last exported, named as on the button when there are several; null when
 * there is no such line to show: the profile was never exported, the profiles or the time have not been read,
 * or the time is ANOTHER profile's than the one [profiles] says is on. The two arrive from two readings, and
 * in the moment of a switch the new profile's name would stand beside the old one's time: a profile never
 * exported would be shown as exported.
 */
internal fun lastBackupText(res: Resources, profiles: RestoreProfiles?, last: LastBackup?, format: (Long) -> String): String? {
    if (profiles == null || last == null || last.timeMs <= 0L || last.homeProfileId != profiles.activeId) return null
    val formatted = format(last.timeMs)
    val profile = exportedProfileName(res, profiles) ?: return res.getString(R.string.backup_last_time, formatted)
    return res.getString(R.string.backup_last_time_profile, profile, formatted)
}

/** What the screen shows for an export that failed with [error]. */
internal fun exportFailureEvent(error: Throwable): BackupEvent = when (error) {
    is ExportProfileChangedException -> BackupEvent.ExportProfileChanged
    else -> BackupEvent.Failed(error.message.orEmpty())
}
